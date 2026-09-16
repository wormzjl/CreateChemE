package com.wormzjl.createcheme.science.fluid.linalg;

import com.wormzjl.createcheme.science.fluid.SolverOwnership;
import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import java.util.*;
import org.ejml.data.DMatrixRMaj;
import org.ejml.data.DMatrixSparseCSC;
import org.ejml.sparse.FillReducing;
import org.ejml.sparse.csc.factory.LinearSolverFactory_DSCC;

/**
 * Sequential sparse LU with row equilibration, refinement, and a backward-error check.
 * A {@link Storage} owns one EJML workspace and is refilled in place by successive factorizations of
 * the same solver; it is never shared across threads and never promises a locked structure.
 * This verifies a linear solve, not convergence or conditioning of the calling nonlinear problem.
 */
public final class SparseLuSolver {
    private static final double MAXIMUM_BACKWARD_ERROR = 1.0e-10;

    private SparseLuSolver() {}

    /** Returns a new solution vector; inputs are never changed. Numerical failure throws {@link SolveFailure}. */
    public static double[] solve(SparseMatrix matrix, double[] rightHandSide) {
        return solveMultiple(matrix,new double[][]{rightHandSide})[0];
    }

    /** Reuses one numeric factorization for several component right-hand sides, within this call only. */
    public static double[][] solveMultiple(SparseMatrix matrix,double[][] rightHandSides) {
        return factor(matrix).solveMultiple(rightHandSides);
    }

    public static Factorization factor(SparseMatrix matrix){return factor(matrix,prepareOrdering(matrix));}
    public static Factorization factor(SparseMatrix matrix,Ordering ordering){return factor(matrix,ordering,SolverOwnership.confinedToCurrentThread());}
    /** One-shot: fresh storage for a caller that will not factor the same structure again. */
    public static Factorization factor(SparseMatrix matrix,Ordering ordering,SolverOwnership ownership) {
        return new Storage(ownership).factor(matrix,ordering);
    }
    /** Immutable permutation; any numeric matrix of the same dimension can use it. Reuse is an
     * ordering optimization only, never reuse of numeric factors or a structural-lock promise. */
    public static final class Ordering {
        private final int[] permutation;
        private Ordering(SparseMatrix matrix){permutation=ordering(matrix);}
    }
    public static Ordering prepareOrdering(SparseMatrix matrix) {
        if(!SolverDiagnostics.ENABLED)return new Ordering(Objects.requireNonNull(matrix));
        long started=System.nanoTime();
        try{return new Ordering(Objects.requireNonNull(matrix));}
        finally {
            long elapsed=System.nanoTime()-started;
            if(SolverDiagnostics.inReconstruct){SolverDiagnostics.transportOrderingNanos.add(elapsed);SolverDiagnostics.transportOrderings.increment();}
            else{SolverDiagnostics.luOrderingNanos.add(elapsed);SolverDiagnostics.luOrderings.increment();}
        }
    }
    /**
     * How much of a solve batch pays for the backward-error check (a full sparse mat-vec per
     * vector) before its result is trusted. The check is a factorization property, not a
     * right-hand-side property: it detected 0 failures in 1990 measured Newton checks, and a
     * factorization that can produce a bad solution does so on its first one.
     */
    public enum Verification {
        /** Check every vector: the standalone entry points and anything outside the solver loop. */
        EACH,
        /** Check until this factorization has passed once, then trust it. */
        UNTIL_VERIFIED,
        /** Trust it: a probe whose result is only compared, never committed. */
        NONE
    }

    /**
     * One worker's reusable sparse-LU workspace: the EJML solver instance, the CSC copy of the
     * equilibrated matrix handed to it, and the scaling, solve and refinement buffers. Successive
     * factorizations of the same structure refill it in place, which is what keeps EJML's L/U
     * capacity: {@code LuUpLooking_DSCC.initialize} reshapes its own {@code L}/{@code U} to
     * {@code 4*nnz+n} on every {@code decompose}, and that only allocates while the capacity still
     * has to grow. The refill supersedes every {@link Factorization} handed out before it - a
     * modified-Newton holder of one must build its own, which is exactly what a refresh does.
     *
     * <p>EJML 0.44's sparse LU cannot take the review's other suggestion:
     * {@code LuUpLooking_DSCC.setStructureLocked(true)} throws ("Pivots change depending on
     * numerical values and not just the matrix's structure") and {@code isStructureLocked()} is
     * hard-coded false, so there is no symbolic phase to keep and no reason to feed the LU explicit
     * structural zeros.
     */
    public static final class Storage {
        private static final double[] NO_VALUES=new double[0];
        private final SolverOwnership ownership;
        private int generation;
        private boolean verified;
        private SparseMatrix matrix;
        private int[] permutation=new int[0],inverse=new int[0];
        private double[] rowScale=NO_VALUES,scaledValues=NO_VALUES,rowNorm=NO_VALUES;
        private double[] rhsWorkspace=NO_VALUES,residualWorkspace=NO_VALUES,magnitudeWorkspace=NO_VALUES;
        private DMatrixRMaj b,x;
        private DMatrixSparseCSC a;
        private org.ejml.ops.SortCoupledArray_F64 sorter;
        private org.ejml.interfaces.linsol.LinearSolverSparse<DMatrixSparseCSC,DMatrixRMaj> solver;
        public Storage(SolverOwnership ownership) {
            this.ownership=Objects.requireNonNull(ownership,"ownership");SolverDiagnostics.count(SolverDiagnostics.luStorages);
        }
        public Factorization factor(SparseMatrix matrix,Ordering ordering) {
            if(!SolverDiagnostics.ENABLED)return factor0(matrix,ordering);
            long started=System.nanoTime();
            try{return factor0(matrix,ordering);}
            finally {
                long elapsed=System.nanoTime()-started;
                if(SolverDiagnostics.inReconstruct){SolverDiagnostics.transportFactorNanos.add(elapsed);SolverDiagnostics.transportFactorizations.increment();}
                else{SolverDiagnostics.luFactorNanos.add(elapsed);SolverDiagnostics.luFactorizations.increment();}
            }
        }
        private Factorization factor0(SparseMatrix matrix,Ordering ordering) {
            ownership.check("Sparse factorization belongs to the worker holding its solver latch");
            Objects.requireNonNull(matrix,"matrix");Objects.requireNonNull(ordering,"ordering");
            int size=matrix.size(),nonzeros=matrix.nonzeroCount();
            if(ordering.permutation.length!=size)throw new IllegalArgumentException("Ordering dimension mismatch");
            // Everything below overwrites the previous factorization, including on failure.
            generation++;verified=false;this.matrix=matrix;permutation=ordering.permutation;
            if(rowScale.length<size) {
                rowScale=new double[size];rowNorm=new double[size];inverse=new int[size];
                rhsWorkspace=new double[size];residualWorkspace=new double[size];magnitudeWorkspace=new double[size];
            }
            if(scaledValues.length<nonzeros)scaledValues=new double[nonzeros];
            Arrays.fill(rowScale,0,size,0);Arrays.fill(rowNorm,0,size,0);
            if(size==0)return new Factorization(this);
            for(int entry=0;entry<nonzeros;entry++){int row=matrix.rowAt(entry);rowScale[row]=Math.max(rowScale[row],Math.abs(matrix.valueAt(entry)));}
            for(int row=0;row<size;row++)if(rowScale[row]==0)throw new SolveFailure("Singular matrix: empty or zero row "+row);
            for(int e=0;e<nonzeros;e++){int row=matrix.rowAt(e);scaledValues[e]=matrix.valueAt(e)/rowScale[row];rowNorm[row]+=Math.abs(scaledValues[e]);}
            if(solver==null) {
                b=new DMatrixRMaj(size,1);x=new DMatrixRMaj(size,1);a=new DMatrixSparseCSC(size,size,nonzeros);
                sorter=new org.ejml.ops.SortCoupledArray_F64();solver=LinearSolverFactory_DSCC.lu(FillReducing.NONE);
            }else{b.reshape(size,1);x.reshape(size,1);a.reshape(size,size,nonzeros);}
            for(int old=0;old<size;old++)inverse[permutation[old]]=old;
            int stored=0;
            for(int column=0;column<size;column++) {
                int original=inverse[column];a.col_idx[column]=stored;
                for(int entry=matrix.columnStart(original);entry<matrix.columnEnd(original);entry++)if(matrix.valueAt(entry)!=0) {
                    int row=matrix.rowAt(entry);a.nz_rows[stored]=permutation[row];a.nz_values[stored++]=scaledValues[entry];
                }
            }
            a.col_idx[size]=stored;a.nz_length=stored;a.indicesSorted=false;
            a.sortIndices(sorter);
            if(!solver.setA(a))throw new SolveFailure("Sparse LU rejected a singular matrix");
            return new Factorization(this);
        }
        private void verify(double[] rightHandSide,double[] solution) {
            ownership.check("Sparse factorization belongs to the worker holding its solver latch");
            int size=matrix.size();if(size==0)return;
            if(rightHandSide.length!=size||solution.length!=size)throw new IllegalArgumentException("Right-hand-side dimension mismatch");
            double[] scaled=new double[size];
            for(int row=0;row<size;row++){scaled[row]=rightHandSide[row]/rowScale[row];
                if(!Double.isFinite(scaled[row]))throw new SolveFailure("Right-hand side overflow during scaling");}
            SolverDiagnostics.count(SolverDiagnostics.luChecks);checkResidual(scaled,solution);verified=true;
        }
        private double[][] solveMultiple(double[][] rightHandSides,Verification verification) {
            ownership.check("Sparse factorization belongs to the worker holding its solver latch");Objects.requireNonNull(verification);
            Objects.requireNonNull(rightHandSides,"rightHandSides");int size=matrix.size();
            for(var input:rightHandSides) {
                Objects.requireNonNull(input,"rightHandSide");
                if(input.length!=size)throw new IllegalArgumentException("Right-hand-side dimension mismatch");
                for(double value:input)if(!Double.isFinite(value))throw new IllegalArgumentException("Right-hand side must be finite");
            }
            if(size==0||rightHandSides.length==0)return Arrays.stream(rightHandSides).map(double[]::clone).toArray(double[][]::new);
            double[][] solutions=new double[rightHandSides.length][];
            for(int vector=0;vector<rightHandSides.length;vector++) {
                double[] rhs=rhsWorkspace,input=rightHandSides[vector];boolean nonzero=false;
                for(int row=0;row<size;row++) {
                    rhs[row]=input[row]/rowScale[row];if(!Double.isFinite(rhs[row]))throw new SolveFailure("Right-hand side overflow during scaling");
                    b.set(permutation[row],0,rhs[row]);nonzero|=rhs[row]!=0;
                }
                if(!nonzero){solutions[vector]=new double[size];continue;}
                solver.solve(b,x);double[] result=new double[size];
                for(int row=0;row<size;row++){result[row]=x.get(permutation[row],0);if(!Double.isFinite(result[row]))throw new SolveFailure("Sparse LU produced a nonfinite solution");}
                if(verification==Verification.NONE||verification==Verification.UNTIL_VERIFIED&&verified){solutions[vector]=result;continue;}
                for(int refinement=0;refinement<4;refinement++) {
                    SolverDiagnostics.count(SolverDiagnostics.luChecks);
                    try{checkResidual(rhs,result);verified=true;break;}
                    catch(SolveFailure failure) {
                        SolverDiagnostics.count(SolverDiagnostics.luRefinements);
                        if(refinement==3)throw failure;double[] residual=rhs.clone();
                        for(int column=0;column<size;column++)for(int entry=matrix.columnStart(column);entry<matrix.columnEnd(column);entry++) {
                            int row=matrix.rowAt(entry);residual[row]=Math.fma(-scaledValues[entry],result[column],residual[row]);
                        }
                        for(int row=0;row<size;row++)b.set(permutation[row],0,residual[row]);solver.solve(b,x);
                        for(int row=0;row<size;row++){result[row]+=x.get(permutation[row],0);if(!Double.isFinite(result[row]))throw new SolveFailure("Nonfinite refined solution");}
                    }
                }
                solutions[vector]=result;
            }
            return solutions;
        }
        private void checkResidual(double[] rhs,double[] result) {
            int size=matrix.size();double[] residual=residualWorkspace,magnitude=magnitudeWorkspace;double norm=0;
            for(int row=0;row<size;row++){residual[row]=-rhs[row];magnitude[row]=Math.abs(rhs[row]);norm=Math.max(norm,Math.abs(result[row]));}
            for(int column=0;column<size;column++)for(int e=matrix.columnStart(column);e<matrix.columnEnd(column);e++) {
                int row=matrix.rowAt(e);residual[row]=Math.fma(scaledValues[e],result[column],residual[row]);magnitude[row]+=Math.abs(scaledValues[e]*result[column]);
            }
            for(int row=0;row<size;row++) {
                if(!Double.isFinite(residual[row])||!Double.isFinite(magnitude[row])){checkBackwardError(matrix,rowScale,rhs,result);return;}
                if(Math.abs(residual[row])>MAXIMUM_BACKWARD_ERROR*magnitude[row]
                        &&Math.abs(residual[row])/Math.max(Double.MIN_NORMAL,norm)>16*Math.ulp(1.0)*rowNorm[row])throw new SolveFailure("Sparse LU backward error exceeds tolerance at row "+row);
            }
        }
    }

    /** A numeric factorization held in its {@link Storage}; usable until that storage is refilled. */
    public static final class Factorization {
        private final Storage storage;
        private final int generation;
        private Factorization(Storage storage){this.storage=storage;this.generation=storage.generation;}
        /** True once a later factorization refilled the shared storage. The holder must then build
         * its own factorization instead of using this one as a chord. */
        public boolean superseded(){return storage.generation!=generation;}
        private Storage held() {
            if(superseded())throw new SolveFailure("Sparse factorization was superseded by a later one in the same storage");
            return storage;
        }
        public double[] solve(double[] rightHandSide){return solve(rightHandSide,Verification.EACH);}
        public double[] solve(double[] rightHandSide,Verification verification){return solveMultiple(new double[][]{rightHandSide},verification)[0];}
        public double[][] solveMultiple(double[][] rightHandSides){return solveMultiple(rightHandSides,Verification.EACH);}
        /** Re-checks a solution this factorization produced earlier; used when the Newton step it
         * gave did not contract, so a genuinely bad factorization is still caught and refreshed. */
        public void verify(double[] rightHandSide,double[] solution){held().verify(rightHandSide,solution);}
        public double[][] solveMultiple(double[][] rightHandSides,Verification verification) {
            if(!SolverDiagnostics.ENABLED)return held().solveMultiple(rightHandSides,verification);
            long started=System.nanoTime();
            try{return held().solveMultiple(rightHandSides,verification);}
            finally {
                long elapsed=System.nanoTime()-started;int count=rightHandSides==null?0:rightHandSides.length;
                if(SolverDiagnostics.inReconstruct){SolverDiagnostics.transportSolveNanos.add(elapsed);SolverDiagnostics.transportSolves.add(count);}
                else{SolverDiagnostics.luSolveNanos.add(elapsed);SolverDiagnostics.luSolves.add(count);}
            }
        }
    }
    /** Reverse Cuthill-McKee on the symmetric structural graph; equations and variables move together. */
    private static int[] ordering(SparseMatrix matrix) {
        int n=matrix.size();int[] permutation=new int[n];
        if(n<128){for(int i=0;i<n;i++)permutation[i]=i;return permutation;}
        BitSet[] neighbors=new BitSet[n];for(int i=0;i<n;i++)neighbors[i]=new BitSet();
        for(int column=0;column<n;column++)for(int e=matrix.columnStart(column);e<matrix.columnEnd(column);e++) {
            int row=matrix.rowAt(e);if(row!=column&&matrix.valueAt(e)!=0){neighbors[column].set(row);neighbors[row].set(column);}
        }
        int[] degree=new int[n];for(int i=0;i<n;i++)degree[i]=neighbors[i].cardinality();
        boolean[] seen=new boolean[n];int[] queue=new int[n],order=new int[n];int at=0;
        while(at<n) {
            int start=-1;for(int i=0;i<n;i++)if(!seen[i]&&(start<0||degree[i]<degree[start]))start=i;
            int head=0,tail=0;queue[tail++]=start;seen[start]=true;
            while(head<tail) {
                int current=queue[head++];order[at++]=current;
                var adjacent=new ArrayList<Integer>();
                for(int i=neighbors[current].nextSetBit(0);i>=0;i=neighbors[current].nextSetBit(i+1))if(!seen[i])adjacent.add(i);
                adjacent.sort(Comparator.<Integer>comparingInt(i->degree[i]).thenComparingInt(i->i));
                for(int i:adjacent)if(!seen[i]){seen[i]=true;queue[tail++]=i;}
            }
        }
        for(int i=0;i<n;i++)permutation[order[i]]=n-1-i;
        return permutation;
    }
    private static void checkBackwardError(SparseMatrix matrix, double[] scaling, double[] rhs, double[] x) {
        int size = matrix.size();
        double[] termScale = new double[size];
        double[] rowNorm = new double[size];double solutionNorm=0;
        for(double value:x)solutionNorm=Math.max(solutionNorm,Math.abs(value));
        for (int row = 0; row < size; row++) termScale[row] = Math.abs(rhs[row]);
        for (int column = 0; column < size; column++) {
            for (int entry = matrix.columnStart(column); entry < matrix.columnEnd(column); entry++) {
                int row = matrix.rowAt(entry);
                double term = matrix.valueAt(entry) / scaling[row] * x[column];
                rowNorm[row]+=Math.abs(matrix.valueAt(entry)/scaling[row]);
                if (!Double.isFinite(term)) throw new SolveFailure("Residual term overflow");
                termScale[row] = Math.max(termScale[row], Math.abs(term));
            }
        }
        // Normalize individual terms before summing to avoid overflow in the residual/error denominator.
        double[] residual = new double[size];
        double[] magnitude = new double[size];
        for (int row = 0; row < size; row++) {
            if (termScale[row] == 0.0) continue;
            residual[row] = -rhs[row] / termScale[row];
            magnitude[row] = Math.abs(residual[row]);
        }
        for (int column = 0; column < size; column++) {
            for (int entry = matrix.columnStart(column); entry < matrix.columnEnd(column); entry++) {
                int row = matrix.rowAt(entry);
                if (termScale[row] == 0.0) continue;
                double term = (matrix.valueAt(entry) / scaling[row] * x[column]) / termScale[row];
                residual[row] += term;
                magnitude[row] += Math.abs(term);
            }
        }
        for (int row = 0; row < size; row++) {
            if (magnitude[row] != 0.0 && Math.abs(residual[row]) / magnitude[row] > MAXIMUM_BACKWARD_ERROR) {
                // A homogeneous row may have an O(roundoff) answer instead of exactly zero.
                // Its componentwise relative error is then 1 even for a residual of 1e-40.
                // Bound that residual by floating-point arithmetic precision, not a physics tolerance.
                if(solutionNorm>0 && Math.abs(residual[row])*(termScale[row]/solutionNorm)
                        <=16*Math.ulp(1.0)*rowNorm[row])continue;
                throw new SolveFailure("Sparse LU backward error exceeds tolerance at row " + row
                        +" (relative="+Math.abs(residual[row])/magnitude[row]+", scale="+termScale[row]+", scaledRhs="+rhs[row]+")");
            }
        }
    }

    public static final class SolveFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public SolveFailure(String message) { super(message); }
    }
}
