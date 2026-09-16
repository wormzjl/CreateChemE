package com.wormzjl.createcheme.science.fluid.solver;

import com.wormzjl.createcheme.science.fluid.linalg.SparseLuSolver;
import com.wormzjl.createcheme.science.fluid.linalg.SparseMatrix;
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
        public Nonconvergence(String message){this(message,null);}
        public Nonconvergence(String message,double[] lastVariables){super(message);this.lastVariables=lastVariables==null?null:lastVariables.clone();}
        public double[] lastVariables(){return lastVariables==null?null:lastVariables.clone();}
    }

    /** Reusable modified-Newton workspace for one unchanged equation structure on one worker. */
    public static final class Workspace {
        private final Thread owner=Thread.currentThread();
        private Pattern pattern;
        private SparseMatrix matrix;
        private SparseLuSolver.Factorization factorization;
        private SparseLuSolver.Ordering ordering;
        private void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Newton workspace belongs to its creating worker");}
        public void invalidate(){owned();matrix=null;factorization=null;}
        /** Reuse immutable sparsity/coloring/order for another timestep, with fresh numeric factors. */
        public Workspace forkStructure(){owned();var fork=new Workspace();fork.pattern=pattern;fork.ordering=ordering;return fork;}
        /** Seed modified Newton with the previous timestep's Jacobian. All uses remain sequential
         * on this worker; poor contraction or failed line search refreshes from current equations.
         * The current nonlinear residual, not this approximation, decides convergence. */
        public Workspace forkPreconditioner(){owned();var fork=forkStructure();fork.matrix=matrix;fork.factorization=factorization;return fork;}
    }

    public static Result solve(Equations equations,double[] initial,Settings settings,Runnable checkpoint) {
        return solve(equations,initial,settings,checkpoint,new Workspace());
    }
    public static Result solve(Equations equations,double[] initial,Settings settings,Runnable checkpoint,Workspace workspace) {
        Objects.requireNonNull(equations);Objects.requireNonNull(settings);Objects.requireNonNull(checkpoint);
        Objects.requireNonNull(workspace).owned();
        int n=equations.size();if(n==0||initial.length!=n)throw new IllegalArgumentException("Invalid equation dimension");
        if(workspace.pattern==null)workspace.pattern=new Pattern(n,equations.columnRows());
        if(workspace.pattern.offsets.length!=n+1)throw new IllegalArgumentException("Newton workspace structure changed");
        var pattern=workspace.pattern;int calls=1,lastNonzeros=0,iterationsSinceRefresh=0;boolean refresh=workspace.factorization==null;double[] x=initial.clone();
        checkpoint.run();double[] f=evaluate(equations,x,n);double norm=norm(f);
        for(int iteration=0;iteration<=settings.iterations();iteration++) {
            checkpoint.run();
            if(norm<=settings.tolerance())return new Result(x,norm,iteration,calls,lastNonzeros,pattern.groups.size());
            if(iteration==settings.iterations())break;
            boolean fresh=refresh;
            if(refresh) {
                calls+=differentiate(equations,x,f,settings.differenceStep(),checkpoint,workspace);
                lastNonzeros=workspace.matrix.nonzeroCount();refresh=false;iterationsSinceRefresh=0;
            }
            double[] rhs=new double[n];for(int row=0;row<n;row++)rhs[row]=-f[row];
            double[] direction;
            lastNonzeros=workspace.matrix.nonzeroCount();
            try{direction=workspace.factorization.solve(rhs);}
            catch(SparseLuSolver.SolveFailure failure){if(!fresh){refresh=true;continue;}workspace.invalidate();throw new Nonconvergence("Singular Newton Jacobian: "+failure.getMessage(),x);}
            double alpha=Math.min(1,equations.maximumStep(x,direction));boolean accepted=false;
            if(!Double.isFinite(alpha)||alpha<=0)throw new Nonconvergence("No feasible Newton direction",x);
            for(int backtrack=0;backtrack<settings.backtracks();backtrack++,alpha*=.5) {
                checkpoint.run();double[] candidate=x.clone();for(int i=0;i<n;i++)candidate[i]+=alpha*direction[i];
                try {
                    calls++;double[] next=evaluate(equations,candidate,n);double nextNorm=norm(next);
                    // Natural (affine-invariant) merit prevents a nearly exact hydraulic row from
                    // forcing tiny steps while stiff liquid-volume/material rows still need correction.
                    // Acceptance is measured in the same linearized state coordinates as the Newton step.
                    boolean descent=nextNorm<norm*(1-1e-4*alpha)||nextNorm<=settings.tolerance();
                    double reduction=nextNorm/norm;
                    if(!descent) {
                        double merit=norm(direction),nextMerit;
                        try{nextMerit=norm(workspace.factorization.solve(next));}
                        catch(SparseLuSolver.SolveFailure failure){continue;}
                        descent=nextMerit<merit*(1-1e-4*alpha);reduction=nextMerit/merit;
                    }
                    if(descent) {
                        // Residual evaluation and triangular solves are much cheaper than a new
                        // colored Jacobian. Keep a contracting chord iteration a little longer;
                        // stalled searches still force a fresh Jacobian immediately.
                        refresh=reduction>.8||++iterationsSinceRefresh>=8;
                        x=candidate;f=next;norm=nextNorm;accepted=true;break;
                    }
                }catch(IllegalArgumentException outsideDomain){ /* A smaller Newton step may stay in the valid domain. */ }
            }
            if(!accepted){if(!fresh){refresh=true;continue;}workspace.invalidate();throw new Nonconvergence("Newton line search stalled at residual "+norm,x);}
        }
        workspace.invalidate();
        throw new Nonconvergence("Newton iteration limit at residual "+norm,x);
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
            checkpoint.run();if(workspace.factorization==null||attempt>0)differentiate(equations,point,residual,1e-6,checkpoint,workspace);
            try {
                var correction=workspace.factorization.solve(rhs);var probe=point.clone();for(int i=0;i<n;i++)probe[i]+=correction[i];
                double after=norm(evaluate(equations,probe,n));
                if(after<=Math.max(1e-10,.1*before))return new CorrectionEstimate(probe,before,after);
            }catch(IllegalArgumentException|SparseLuSolver.SolveFailure invalidProbe){ /* Refresh before refusing the estimate. */ }
        }
        throw new Nonconvergence("Linearized error estimate does not contract inside the supported domain");
    }
    private static int differentiate(Equations equations,double[] x,double[] f,double differenceStep,Runnable checkpoint,Workspace workspace) {
        var pattern=workspace.pattern;int n=x.length,calls=0;double[] derivatives=new double[pattern.rows.length],steps=new double[n];
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
        var numeric=pattern.numericMatrix(derivatives);
        try{if(workspace.ordering==null)workspace.ordering=SparseLuSolver.prepareOrdering(pattern.symbolicMatrix());workspace.factorization=SparseLuSolver.factor(numeric,workspace.ordering);workspace.matrix=numeric;}
        catch(SparseLuSolver.SolveFailure failure){workspace.invalidate();throw new Nonconvergence("Singular Newton Jacobian: "+failure.getMessage(),x);}
        return calls;
    }
    private static double[] evaluate(Equations equations,double[] variables,int size) {
        for(double value:variables)if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite trial variable");
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
            return new SparseMatrix(offsets.length-1,starts,numericRows,numericValues);
        }
        SparseMatrix symbolicMatrix() {
            // A zero-flow startup hides couplings that become nonzero as transport starts.
            // Order using the stable dependency graph; numeric LU still receives only nonzeros.
            double[] entries=new double[rows.length];Arrays.fill(entries,1);return new SparseMatrix(offsets.length-1,offsets,rows,entries);
        }
    }
}
