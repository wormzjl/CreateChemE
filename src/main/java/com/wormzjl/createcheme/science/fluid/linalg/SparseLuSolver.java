package com.wormzjl.createcheme.science.fluid.linalg;

import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import java.util.*;
import org.ejml.data.DMatrixRMaj;
import org.ejml.data.DMatrixSparseCSC;
import org.ejml.sparse.FillReducing;
import org.ejml.sparse.csc.factory.LinearSolverFactory_DSCC;

/**
 * Sequential sparse LU with row equilibration, refinement, and a backward-error check.
 * Each call owns its EJML workspace. No shared mutable solver, internal executor, or structure-lock promise.
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
        finally{SolverDiagnostics.luOrderingNanos.add(System.nanoTime()-started);SolverDiagnostics.luOrderings.increment();}
    }
    public static Factorization factor(SparseMatrix matrix,Ordering ordering) {
        if(!SolverDiagnostics.ENABLED)return new Factorization(matrix,Objects.requireNonNull(ordering));
        long started=System.nanoTime();
        try{return new Factorization(matrix,Objects.requireNonNull(ordering));}
        finally {
            long elapsed=System.nanoTime()-started;
            if(SolverDiagnostics.inReconstruct){SolverDiagnostics.transportFactorNanos.add(elapsed);SolverDiagnostics.transportFactorizations.increment();}
            else{SolverDiagnostics.luFactorNanos.add(elapsed);SolverDiagnostics.luFactorizations.increment();}
        }
    }

    /** A reusable numeric factorization owned by the creating worker, never shared across threads. */
    public static final class Factorization {
        private final Thread owner=Thread.currentThread();
        private final SparseMatrix matrix;
        private final double[] rowScale;
        private final int[] permutation;
        private final double[] scaledValues,rowNorm;
        private final double[] rhsWorkspace,residualWorkspace,magnitudeWorkspace;
        private final DMatrixRMaj b,x;
        private final org.ejml.interfaces.linsol.LinearSolverSparse<DMatrixSparseCSC,DMatrixRMaj> solver;
        private Factorization(SparseMatrix matrix,Ordering ordering) {
            this.matrix=Objects.requireNonNull(matrix,"matrix");int size=matrix.size();rowScale=new double[size];
            if(ordering.permutation.length!=size)throw new IllegalArgumentException("Ordering dimension mismatch");permutation=ordering.permutation;
            scaledValues=new double[matrix.nonzeroCount()];rowNorm=new double[size];
            rhsWorkspace=new double[size];residualWorkspace=new double[size];magnitudeWorkspace=new double[size];
            b=new DMatrixRMaj(size,1);x=new DMatrixRMaj(size,1);
            if(size==0){solver=null;return;}
            for(int entry=0;entry<matrix.nonzeroCount();entry++){int row=matrix.rowAt(entry);rowScale[row]=Math.max(rowScale[row],Math.abs(matrix.valueAt(entry)));}
            for(int row=0;row<size;row++)if(rowScale[row]==0)throw new SolveFailure("Singular matrix: empty or zero row "+row);
            for(int e=0;e<scaledValues.length;e++){int row=matrix.rowAt(e);scaledValues[e]=matrix.valueAt(e)/rowScale[row];rowNorm[row]+=Math.abs(scaledValues[e]);}
            var a=new DMatrixSparseCSC(size,size,matrix.nonzeroCount());int[] inverse=new int[size];
            for(int old=0;old<size;old++)inverse[permutation[old]]=old;
            int stored=0;
            for(int column=0;column<size;column++) {
                int original=inverse[column];a.col_idx[column]=stored;
                for(int entry=matrix.columnStart(original);entry<matrix.columnEnd(original);entry++)if(matrix.valueAt(entry)!=0) {
                    int row=matrix.rowAt(entry);a.nz_rows[stored]=permutation[row];a.nz_values[stored++]=scaledValues[entry];
                }
            }
            a.col_idx[size]=stored;a.nz_length=stored;a.indicesSorted=false;
            a.sortIndices(null);
            solver=LinearSolverFactory_DSCC.lu(FillReducing.NONE);
            if(!solver.setA(a))throw new SolveFailure("Sparse LU rejected a singular matrix");
        }
        public double[] solve(double[] rightHandSide){return solveMultiple(new double[][]{rightHandSide})[0];}
        public double[][] solveMultiple(double[][] rightHandSides) {
            if(!SolverDiagnostics.ENABLED)return solveMultiple0(rightHandSides);
            long started=System.nanoTime();
            try{return solveMultiple0(rightHandSides);}
            finally {
                long elapsed=System.nanoTime()-started;int count=rightHandSides==null?0:rightHandSides.length;
                if(SolverDiagnostics.inReconstruct){SolverDiagnostics.transportSolveNanos.add(elapsed);SolverDiagnostics.transportSolves.add(count);}
                else{SolverDiagnostics.luSolveNanos.add(elapsed);SolverDiagnostics.luSolves.add(count);}
            }
        }
        private double[][] solveMultiple0(double[][] rightHandSides) {
            if(Thread.currentThread()!=owner)throw new IllegalStateException("Sparse factorization belongs to its creating worker");
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
                for(int refinement=0;refinement<4;refinement++) {
                    SolverDiagnostics.count(SolverDiagnostics.luChecks);
                    try{checkResidual(rhs,result);break;}
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
