package com.wormzjl.createcheme.science.fluid.solver;

import com.wormzjl.createcheme.science.fluid.linalg.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StructuralReuseTest {
    @Test void forkReusesStructureButMustBuildFreshNumericFactorsForChangedCoefficients() {
        var workspace=new SparseNewton.Workspace();var settings=new SparseNewton.Settings(1,1e-12,1,8);
        var first=new SparseNewton.Equations(){public int size(){return 1;}public int[][] columnRows(){return new int[][]{{0}};}public double[] residual(double[] x){return new double[]{2*x[0]-4};}};
        assertEquals(2,SparseNewton.solve(first,new double[]{0},settings,()->{},workspace).variables()[0]);
        var second=new SparseNewton.Equations(){public int size(){return 1;}public int[][] columnRows(){throw new AssertionError("Sparsity should already be compiled");}public double[] residual(double[] x){return new double[]{4*x[0]-16};}};
        assertEquals(4,SparseNewton.solve(second,new double[]{0},settings,()->{},workspace.forkStructure()).variables()[0]);
        assertEquals(2,SparseNewton.solve(first,new double[]{0},settings,()->{},workspace).variables()[0]);
    }
    @Test void reusedPermutationRemainsValidWhenNumericZerosBecomeNonzero() {
        int n=130;int[] diagonalOffsets=new int[n+1],diagonalRows=new int[n];double[] diagonal=new double[n];
        for(int i=0;i<n;i++){diagonalOffsets[i]=i;diagonalRows[i]=i;diagonal[i]=2;}diagonalOffsets[n]=n;
        var ordering=SparseLuSolver.prepareOrdering(new SparseMatrix(n,diagonalOffsets,diagonalRows,diagonal));
        int[] offsets=new int[n+1];var rows=new ArrayList<Integer>();var values=new ArrayList<Double>();double[] rhs=new double[n];
        for(int col=0;col<n;col++){offsets[col]=rows.size();for(int row=Math.max(0,col-1);row<=Math.min(n-1,col+1);row++){rows.add(row);values.add(row==col?5.0:-1.0);rhs[row]+=row==col?5:-1;}}offsets[n]=rows.size();
        var changed=new SparseMatrix(n,offsets,rows.stream().mapToInt(Integer::intValue).toArray(),values.stream().mapToDouble(Double::doubleValue).toArray());
        for(double value:SparseLuSolver.factor(changed,ordering).solve(rhs))assertEquals(1,value,1e-12);
        assertThrows(IllegalArgumentException.class,()->SparseLuSolver.factor(new SparseMatrix(1,new int[]{0,1},new int[]{0},new double[]{1}),ordering));
    }
    @Test void staleTimestepPreconditionerCannotDetermineConvergenceAgainstOldEquations() {
        var workspace=new SparseNewton.Workspace();var settings=new SparseNewton.Settings(8,1e-12,1,8);
        var first=new SparseNewton.Equations(){public int size(){return 1;}public int[][] columnRows(){return new int[][]{{0}};}public double[] residual(double[] x){return new double[]{2*x[0]-4};}};
        SparseNewton.solve(first,new double[]{0},settings,()->{},workspace);
        var changed=new SparseNewton.Equations(){public int size(){return 1;}public int[][] columnRows(){return new int[][]{{0}};}public double[] residual(double[] x){return new double[]{-4*x[0]-16};}};
        var result=SparseNewton.solve(changed,new double[]{0},settings,()->{},workspace.forkPreconditioner());
        assertEquals(-4,result.variables()[0]);assertEquals(0,changed.residual(result.variables())[0]);assertEquals(2,SparseNewton.solve(first,new double[]{0},settings,()->{},workspace).variables()[0]);
    }
}
