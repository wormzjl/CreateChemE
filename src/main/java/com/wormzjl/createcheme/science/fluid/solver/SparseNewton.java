package com.wormzjl.createcheme.science.fluid.solver;

import com.wormzjl.createcheme.science.fluid.SolverOwnership;
import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.linalg.SparseLuSolver;
import com.wormzjl.createcheme.science.fluid.linalg.SparseMatrix;
import com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation;
import java.util.*;

/** Per-call sparse Newton workspace. Inputs and residuals must already be dimensionlessly scaled. */
public final class SparseNewton {
    private SparseNewton() {}

    public interface Equations {
        int size();
        /** Must not mutate variables. IllegalArgumentException denotes a trial outside the property domain. */
        double[] residual(double[] variables);
        /** Sorted unique residual rows influenced by each variable. Include structurally possible zero derivatives. */
        int[][] columnRows();
        default double differenceScale(int column,double value){return Math.max(1,Math.abs(value));}
        default double maximumStep(double[] variables,double[] direction){return 1;}
        /**
         * Maps a line-search trial {@code candidate} (the current point {@code variables} plus a
         * scaled step) onto the admissible side of a bound that {@link #maximumStep} deliberately
         * leaves out of the step length, in place. Called for every trial, never for a Jacobian
         * perturbation. The default leaves every trial as it is.
         */
        default void project(double[] variables,double[] candidate){}
        /**
         * Optional block-structured Jacobian: fills {@code entries}, the values of the pattern
         * {@code columnRows()} declares in column-major order ({@code entryOffsets} indexes it per
         * column, {@code entryRows} is the row of each entry), with the same one-sided differences
         * {@link #residual} would produce column by column. An implementation that can re-evaluate
         * one perturbed column's rows without a whole-system residual pass replaces the coloured
         * sweep with it; the arithmetic of every entry must be the coloured sweep's, which is why
         * the row formulas have to be shared rather than restated.
         *
         * <p>Returns the number of residual evaluations to charge the caller, or a negative number
         * when this call could not be completed - a trial outside the property domain, say - and
         * the coloured sweep must run instead. A refusal may leave {@code entries} partly written;
         * the coloured sweep overwrites every one of them.</p>
         */
        default int differentiateEntries(double[] variables,double[] residual,double differenceStep,
                                         int[] entryOffsets,int[] entryRows,double[] entries,Runnable checkpoint) {
            return -1;
        }
    }
    public record Settings(int iterations,double tolerance,double differenceStep,int backtracks) {
        public Settings {
            if(iterations<1||!Double.isFinite(tolerance)||tolerance<=0||!Double.isFinite(differenceStep)
                    ||differenceStep<=0||backtracks<1)throw new IllegalArgumentException("Invalid Newton settings");
        }
        public static Settings defaults(){return new Settings(20,1e-8,1e-6,24);}
    }
    public record Result(double[] variables,double residualNorm,int iterations,int residualEvaluations,int jacobianNonzeros,int colors) {
        public Result {variables=variables.clone();}
        @Override public double[] variables(){return variables.clone();}
    }
    public static final class Nonconvergence extends RuntimeException {
        private final double[] lastVariables;
        private final ThermoDomainViolation domainViolation;
        public Nonconvergence(String message){this(message,null);}
        public Nonconvergence(String message,double[] lastVariables){this(message,lastVariables,null);}
        /** {@code domainViolation}: the property domain stopped this solve - the failing iteration's line search was
         * refused by it - and a rejection count or a held island should name it rather than the Newton symptom. */
        public Nonconvergence(String message,double[] lastVariables,ThermoDomainViolation domainViolation) {
            super(message);this.lastVariables=lastVariables==null?null:lastVariables.clone();this.domainViolation=domainViolation;
        }
        public double[] lastVariables(){return lastVariables==null?null:lastVariables.clone();}
        /** The domain violation that stopped this solve, or {@code null} when the failure was numerical. */
        public ThermoDomainViolation domainViolation(){return domainViolation;}
    }
    /** The domain violation a refusal stands for: the violation itself, or the one a failed solve carries; else null. */
    public static ThermoDomainViolation domainViolation(Throwable refusal) {
        if(refusal instanceof ThermoDomainViolation violation)return violation;
        if(refusal instanceof Nonconvergence failure)return failure.domainViolation();
        return null;
    }

    /** Reusable modified-Newton workspace for one unchanged equation structure on one worker. */
    public static final class Workspace {
        /** Difference and factorization storage shared by a whole fork family: one timestep's
         * workspace is forked from the previous one, they are used strictly in sequence, and a
         * refactorization by any of them supersedes the older factors, which is the same event a
         * modified-Newton refresh already handles. */
        private static final class Shared {
            private final SparseLuSolver.Storage factors;
            private double[] derivatives=new double[0],steps=new double[0];
            private Shared(SolverOwnership ownership){factors=new SparseLuSolver.Storage(ownership);}
        }
        private final SolverOwnership ownership;
        private final Shared shared;
        private Pattern pattern;
        private SparseMatrix matrix;
        private SparseLuSolver.Factorization factorization;
        private SparseLuSolver.Ordering ordering;
        public Workspace(){this(SolverOwnership.confinedToCurrentThread());}
        /** Retained across jobs: the latch, not the creating thread, decides who may use it. */
        public Workspace(SolverOwnership ownership) {
            this.ownership=Objects.requireNonNull(ownership);shared=new Shared(ownership);
        }
        private Workspace(Workspace parent){ownership=parent.ownership;shared=parent.shared;}
        private void owned(){ownership.check("Newton workspace belongs to the worker holding its solver latch");}
        public void invalidate(){owned();matrix=null;factorization=null;}
        /** Reuse immutable sparsity/coloring/order for another timestep, with fresh numeric factors. */
        public Workspace forkStructure(){owned();var fork=new Workspace(this);fork.pattern=pattern;fork.ordering=ordering;return fork;}
        /** Seed modified Newton with the previous timestep's Jacobian. All uses remain sequential
         * on this worker; poor contraction, a failed line search or a sibling's refactorization
         * refreshes from current equations. The current nonlinear residual, not this approximation,
         * decides convergence. */
        public Workspace forkPreconditioner(){owned();var fork=forkStructure();fork.matrix=matrix;fork.factorization=factorization;return fork;}
        /** A usable preconditioner: built, and not superseded by a sibling timestep's refresh. */
        private boolean preconditioned() {
            if(factorization==null)return false;
            if(!factorization.superseded())return true;
            SolverDiagnostics.count(SolverDiagnostics.luSupersededFactorizations);matrix=null;factorization=null;return false;
        }
        private double[] derivatives(int length) {
            if(shared.derivatives.length!=length)shared.derivatives=new double[length];
            return shared.derivatives;
        }
        private double[] steps(int length) {
            if(shared.steps.length!=length)shared.steps=new double[length];
            return shared.steps;
        }
    }

    public static Result solve(Equations equations,double[] initial,Settings settings,Runnable checkpoint) {
        return solve(equations,initial,settings,checkpoint,new Workspace());
    }
    public static Result solve(Equations equations,double[] initial,Settings settings,Runnable checkpoint,Workspace workspace) {
        Objects.requireNonNull(equations);Objects.requireNonNull(settings);Objects.requireNonNull(checkpoint);
        Objects.requireNonNull(workspace).owned();SolverDiagnostics.count(SolverDiagnostics.newtonSolves);
        int n=equations.size();if(n==0||initial.length!=n)throw new IllegalArgumentException("Invalid equation dimension");
        if(workspace.pattern==null)workspace.pattern=new Pattern(n,equations.columnRows());
        if(workspace.pattern.offsets.length!=n+1)throw new IllegalArgumentException("Newton workspace structure changed");
        var pattern=workspace.pattern;int calls=1,lastNonzeros=0,iterationsSinceRefresh=0;boolean refresh=!workspace.preconditioned();double[] x=initial.clone();
        boolean openedPreconditioned=!refresh;
        if(openedPreconditioned)SolverDiagnostics.count(SolverDiagnostics.newtonSolvesPreconditioned);
        checkpoint.run();double[] f=evaluate(equations,x,n);double norm=norm(f);
        // The property-domain refusal the current iteration's line search met, if any: when that iteration is the one
        // that fails, the failure is the domain's and says so.
        ThermoDomainViolation domain=null;
        for(int iteration=0;iteration<=settings.iterations();iteration++) {
            checkpoint.run();
            if(norm<=settings.tolerance())return new Result(x,norm,iteration,calls,lastNonzeros,pattern.groups.size());
            if(iteration==settings.iterations())break;
            domain=null;
            SolverDiagnostics.count(SolverDiagnostics.newtonIterations);
            if(openedPreconditioned)SolverDiagnostics.count(SolverDiagnostics.newtonIterationsPreconditioned);
            boolean fresh=refresh;
            SolverDiagnostics.count(fresh?SolverDiagnostics.newtonIterationsFresh:SolverDiagnostics.newtonIterationsStale);
            if(refresh) {
                calls+=differentiate(equations,x,f,settings.differenceStep(),checkpoint,workspace);
                lastNonzeros=workspace.matrix.nonzeroCount();refresh=false;iterationsSinceRefresh=0;
            }
            double[] rhs=new double[n];for(int row=0;row<n;row++)rhs[row]=-f[row];
            double[] direction;
            lastNonzeros=workspace.matrix.nonzeroCount();
            try{direction=workspace.factorization.solve(rhs,SparseLuSolver.Verification.UNTIL_VERIFIED);}
            catch(SparseLuSolver.SolveFailure failure){if(!fresh){SolverDiagnostics.count(SolverDiagnostics.newtonRefreshesFailed);refresh=true;continue;}workspace.invalidate();throw new Nonconvergence("Singular Newton Jacobian: "+failure.getMessage(),x);}
            double alpha=Math.min(1,equations.maximumStep(x,direction));boolean accepted=false;
            if(!Double.isFinite(alpha)||alpha<=0)throw new Nonconvergence("No feasible Newton direction",x);
            for(int backtrack=0;backtrack<settings.backtracks();backtrack++,alpha*=.5) {
                checkpoint.run();if(backtrack>0)SolverDiagnostics.count(SolverDiagnostics.newtonBacktracks);
                double[] candidate=x.clone();for(int i=0;i<n;i++)candidate[i]+=alpha*direction[i];
                equations.project(x,candidate);
                try {
                    calls++;SolverDiagnostics.count(fresh?SolverDiagnostics.newtonStepEvaluationsFresh:SolverDiagnostics.newtonStepEvaluationsStale);
                    double[] next=evaluate(equations,candidate,n);double nextNorm=norm(next);
                    // Natural (affine-invariant) merit prevents a nearly exact hydraulic row from
                    // forcing tiny steps while stiff liquid-volume/material rows still need correction.
                    // Acceptance is measured in the same linearized state coordinates as the Newton step.
                    boolean descent=nextNorm<norm*(1-1e-4*alpha)||nextNorm<=settings.tolerance();
                    double reduction=nextNorm/norm;
                    if(!descent) {
                        double merit=norm(direction),nextMerit;
                        // A merit probe is only compared against the current merit, never committed.
                        SolverDiagnostics.count(SolverDiagnostics.newtonMeritProbes);
                        try{nextMerit=norm(workspace.factorization.solve(next,SparseLuSolver.Verification.NONE));}
                        catch(SparseLuSolver.SolveFailure failure){continue;}
                        descent=nextMerit<merit*(1-1e-4*alpha);reduction=nextMerit/merit;
                    }
                    if(descent) {
                        // Residual evaluation and triangular solves are much cheaper than a new
                        // colored Jacobian. Keep a contracting chord iteration a little longer;
                        // stalled searches still force a fresh Jacobian immediately.
                        refresh=reduction>.8||++iterationsSinceRefresh>=8;
                        if(refresh)SolverDiagnostics.count(reduction>.8?SolverDiagnostics.newtonRefreshesStalled:SolverDiagnostics.newtonRefreshesAged);
                        x=candidate;f=next;norm=nextNorm;accepted=true;break;
                    }
                }catch(IllegalArgumentException outsideDomain){
                    // A smaller Newton step may stay in the valid domain.
                    if(outsideDomain instanceof ThermoDomainViolation violation)domain=violation;
                }
            }
            if(!accepted) {
                // A direction that never contracts is the one case where the skipped backward-error
                // check matters: pay it here, so a genuinely bad factorization still refreshes.
                try{workspace.factorization.verify(rhs,direction);}
                catch(SparseLuSolver.SolveFailure failure) {
                    if(!fresh){SolverDiagnostics.count(SolverDiagnostics.newtonRefreshesFailed);refresh=true;continue;}
                    workspace.invalidate();throw new Nonconvergence("Singular Newton Jacobian: "+failure.getMessage(),x);
                }
                if(!fresh){SolverDiagnostics.count(SolverDiagnostics.newtonRefreshesFailed);refresh=true;continue;}
                workspace.invalidate();throw new Nonconvergence("Newton line search stalled at residual "+norm,x,domain);
            }
        }
        workspace.invalidate();
        throw new Nonconvergence("Newton iteration limit at residual "+norm,x,domain);
    }
    public record CorrectionEstimate(double[] probe,double initialResidual,double probeResidual) {
        public CorrectionEstimate{probe=probe.clone();}
        @Override public double[] probe(){return probe.clone();}
    }
    /** Tests a full linearized correction without publishing it; refreshes a stale Jacobian at most once. */
    public static CorrectionEstimate estimateCorrection(Equations equations,double[] point,Runnable checkpoint,Workspace workspace) {
        workspace.owned();int n=equations.size();if(point.length!=n||n==0)throw new IllegalArgumentException("Invalid correction point");
        if(workspace.pattern==null)workspace.pattern=new Pattern(n,equations.columnRows());
        var residual=evaluate(equations,point,n);double before=norm(residual);
        if(before<=1e-12)return new CorrectionEstimate(point,before,before);
        double[] rhs=residual.clone();for(int i=0;i<n;i++)rhs[i]=-rhs[i];
        for(int attempt=0;attempt<2;attempt++) {
            checkpoint.run();if(!workspace.preconditioned()||attempt>0)differentiate(equations,point,residual,1e-6,checkpoint,workspace);
            try {
                var correction=workspace.factorization.solve(rhs);var probe=point.clone();for(int i=0;i<n;i++)probe[i]+=correction[i];
                double after=norm(evaluate(equations,probe,n));
                if(after<=Math.max(1e-10,.1*before))return new CorrectionEstimate(probe,before,after);
            }catch(IllegalArgumentException|SparseLuSolver.SolveFailure invalidProbe){ /* Refresh before refusing the estimate. */ }
        }
        throw new Nonconvergence("Linearized error estimate does not contract inside the supported domain");
    }
    private static int differentiate(Equations equations,double[] x,double[] f,double differenceStep,Runnable checkpoint,Workspace workspace) {
        if(!SolverDiagnostics.ENABLED)return differentiate0(equations,x,f,differenceStep,checkpoint,workspace);
        boolean outer=SolverDiagnostics.enterJacobian();
        try{return differentiate0(equations,x,f,differenceStep,checkpoint,workspace);}
        finally {
            SolverDiagnostics.leaveJacobian(outer);SolverDiagnostics.jacobianBuilds.increment();
            SolverDiagnostics.jacobianColors.add(workspace.pattern.groups.size());
            if(workspace.matrix!=null)SolverDiagnostics.jacobianNonzeros.add(workspace.matrix.nonzeroCount());
        }
    }
    private static int differentiate0(Equations equations,double[] x,double[] f,double differenceStep,Runnable checkpoint,Workspace workspace) {
        var pattern=workspace.pattern;int n=x.length,calls=0;
        double[] derivatives=workspace.derivatives(pattern.rows.length),steps=workspace.steps(n);
        int blocks=equations.differentiateEntries(x,f,differenceStep,pattern.offsets,pattern.rows,derivatives,checkpoint);
        if(blocks>=0) {
            SolverDiagnostics.count(SolverDiagnostics.jacobianBlockBuilds);
            return factor(x,derivatives,workspace,pattern,blocks);
        }
        for(int column=0;column<n;column++)steps[column]=differenceStep*equations.differenceScale(column,x[column]);
        for(int[] group:pattern.groups) {
            checkpoint.run();double[] trial=x.clone();for(int column:group)trial[column]+=steps[column];double[] perturbed=null;
            try{calls++;perturbed=evaluate(equations,trial,n);}catch(IllegalArgumentException outsideDomain){ /* Use a bounded one-sided stencil below. */ }
            if(perturbed!=null){for(int column:group)pattern.derivative(column,derivatives,perturbed,f,steps[column]);continue;}
            for(int column:group) {
                boolean found=false;double step=steps[column];
                for(int reduction=0;reduction<16&&!found;reduction++,step*=.5)for(int sign:new int[]{1,-1}) {
                    checkpoint.run();trial=x.clone();trial[column]+=sign*step;if(trial[column]==x[column])continue;calls++;
                    try{perturbed=evaluate(equations,trial,n);pattern.derivative(column,derivatives,perturbed,f,trial[column]-x[column]);found=true;break;}
                    catch(IllegalArgumentException outsideDomain){ /* Try the other side, then a smaller perturbation. */ }
                }
                if(!found)throw new Nonconvergence("No finite Jacobian trial at variable "+column,x);
            }
        }
        return factor(x,derivatives,workspace,pattern,calls);
    }
    /** The ordering, numeric matrix and factorization are the same whichever sweep filled the entries. */
    private static int factor(double[] x,double[] derivatives,Workspace workspace,Pattern pattern,int calls) {
        var numeric=pattern.numericMatrix(derivatives);
        try{if(workspace.ordering==null)workspace.ordering=SparseLuSolver.prepareOrdering(pattern.symbolicMatrix());workspace.factorization=workspace.shared.factors.factor(numeric,workspace.ordering);workspace.matrix=numeric;}
        catch(SparseLuSolver.SolveFailure failure){workspace.invalidate();throw new Nonconvergence("Singular Newton Jacobian: "+failure.getMessage(),x);}
        return calls;
    }
    private static double[] evaluate(Equations equations,double[] variables,int size) {
        for(double value:variables)if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite trial variable");
        if(SolverDiagnostics.ENABLED) {
            SolverDiagnostics.residualEvaluations.increment();
            if(SolverDiagnostics.inJacobian())SolverDiagnostics.residualEvaluationsInJacobian.increment();
        }
        double[] result=equations.residual(variables);
        if(result.length!=size)throw new IllegalStateException("Equation count changed within a Newton pass");
        for(double value:result)if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite trial residual");
        return result;
    }
    private static double norm(double[] f){double maximum=0;for(double value:f)maximum=Math.max(maximum,Math.abs(value));return maximum;}

    /** Greedy structural coloring avoids one full property sweep for each individual unknown. */
    private static final class Pattern {
        final int[] offsets,rows;
        final List<int[]> groups;
        Pattern(int size,int[][] input) {
            if(input.length!=size)throw new IllegalArgumentException("Jacobian pattern dimension mismatch");
            offsets=new int[size+1];var colorRows=new ArrayList<BitSet>();var columns=new ArrayList<List<Integer>>();
            for(int column=0;column<size;column++) {
                int previous=-1;BitSet occupied=new BitSet(size);
                for(int row:input[column]) {
                    if(row<0||row>=size||row<=previous)throw new IllegalArgumentException("Invalid Jacobian sparsity");
                    previous=row;occupied.set(row);
                }
                offsets[column+1]=Math.addExact(offsets[column],input[column].length);
                int color=0;while(color<colorRows.size()&&colorRows.get(color).intersects(occupied))color++;
                if(color==colorRows.size()){colorRows.add(new BitSet(size));columns.add(new ArrayList<>());}
                colorRows.get(color).or(occupied);columns.get(color).add(column);
            }
            rows=new int[offsets[size]];for(int c=0;c<size;c++)System.arraycopy(input[c],0,rows,offsets[c],input[c].length);
            groups=columns.stream().map(list->list.stream().mapToInt(Integer::intValue).toArray()).toList();
        }
        void derivative(int column,double[] values,double[] perturbed,double[] base,double step) {
            for(int entry=offsets[column];entry<offsets[column+1];entry++)values[entry]=(perturbed[rows[entry]]-base[rows[entry]])/step;
        }
        SparseMatrix numericMatrix(double[] values) {
            // Coloring retains the full structural pattern. Numeric LU must not interpret thousands
            // of exact zero derivatives as actual graph connections and introduce unnecessary fill.
            int[] starts=new int[offsets.length];int count=0;
            for(int c=0;c<offsets.length-1;c++){for(int e=offsets[c];e<offsets[c+1];e++)if(values[e]!=0)count++;starts[c+1]=count;}
            int[] numericRows=new int[count];double[] numericValues=new double[count];int at=0;
            for(int e=0;e<values.length;e++)if(values[e]!=0){numericRows[at]=rows[e];numericValues[at++]=values[e];}
            return SparseMatrix.adopting(offsets.length-1,starts,numericRows,numericValues);
        }
        SparseMatrix symbolicMatrix() {
            // A zero-flow startup hides couplings that become nonzero as transport starts.
            // Order using the stable dependency graph; numeric LU still receives only nonzeros.
            double[] entries=new double[rows.length];Arrays.fill(entries,1);
            return SparseMatrix.adopting(offsets.length-1,offsets.clone(),rows.clone(),entries);
        }
    }
}
