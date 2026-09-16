package com.wormzjl.createcheme.science.fluid.solver;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class SparseNewtonTest {
    @Test void aReusedDerivativeIsOnlyAGuessAndStillConvergesAfterInputChanges() {
        double[] target={2};var equations=new SparseNewton.Equations(){
            public int size(){return 1;}
            public int[][] columnRows(){return new int[][]{{0}};}
            public double[] residual(double[] x){return new double[]{Math.exp(x[0])-target[0]};}
        };
        var workspace=new SparseNewton.Workspace();var first=SparseNewton.solve(equations,new double[]{0},SparseNewton.Settings.defaults(),()->{},workspace);
        target[0]=2.01;var warm=SparseNewton.solve(equations,first.variables(),SparseNewton.Settings.defaults(),()->{},workspace);
        assertEquals(Math.log(2.01),warm.variables()[0],1e-8);
        target[0]=1000;var changed=SparseNewton.solve(equations,warm.variables(),SparseNewton.Settings.defaults(),()->{},workspace);
        assertEquals(Math.log(1000),changed.variables()[0],1e-8);
    }
    @Test void coupledNonlinearSystemConvergesWithoutMutatingTheInitialGuess() {
        var equations=new SparseNewton.Equations(){
            public int size(){return 2;}
            public int[][] columnRows(){return new int[][]{{0,1},{0,1}};}
            public double[] residual(double[] x){return new double[]{x[0]*x[0]+x[1]-5,x[0]+x[1]*x[1]-5};}
        };
        double[] initial={1,1};var result=SparseNewton.solve(equations,initial,SparseNewton.Settings.defaults(),()->{});
        double root=(-1+Math.sqrt(21))/2;assertArrayEquals(new double[]{root,root},result.variables(),1e-8);
        assertArrayEquals(new double[]{1,1},initial);assertTrue(result.residualNorm()<1e-8);
    }
    @Test void structuralColoringBatchesIndependentEquationsAndCancellationPropagates() {
        var equations=new SparseNewton.Equations(){
            public int size(){return 100;}
            public int[][] columnRows(){int[][] rows=new int[100][1];for(int i=0;i<100;i++)rows[i][0]=i;return rows;}
            public double[] residual(double[] x){double[] r=new double[100];for(int i=0;i<100;i++)r[i]=Math.exp(x[i])-2;return r;}
        };
        var result=SparseNewton.solve(equations,new double[100],SparseNewton.Settings.defaults(),()->{});
        assertEquals(1,result.colors());assertEquals(100,result.jacobianNonzeros());assertTrue(result.residualEvaluations()<30);
        assertEquals(Math.log(2),result.variables()[73],1e-8);
        assertThrows(CancellationException.class,()->SparseNewton.solve(equations,new double[100],SparseNewton.Settings.defaults(),()->{throw new CancellationException();}));
    }
    @Test void infeasibleEquationsCannotBeReportedAsConverged() {
        var equations=new SparseNewton.Equations(){
            public int size(){return 1;}
            public int[][] columnRows(){return new int[][]{{0}};}
            public double[] residual(double[] x){return new double[]{x[0]*x[0]+1};}
        };
        assertThrows(SparseNewton.Nonconvergence.class,()->SparseNewton.solve(equations,new double[]{1},SparseNewton.Settings.defaults(),()->{}));
    }
}
