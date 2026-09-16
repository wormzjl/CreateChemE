package com.wormzjl.createcheme.science.fluid;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.createcheme.science.fluid.linalg.SparseLuSolver;
import com.wormzjl.createcheme.science.fluid.linalg.SparseMatrix;
import org.junit.jupiter.api.Test;

class SparseLuSolverTest {
    @Test void reorderedLargeNonsymmetricSystemPreservesSolutionAndMultipleRightHandSides() {
        int size=193;int[] offsets=new int[size+1];var rows=new java.util.ArrayList<Integer>();var values=new java.util.ArrayList<Double>();
        double[] expected=new double[size],rhs=new double[size];for(int i=0;i<size;i++)expected[i]=Math.sin(i*.3);
        for(int c=0;c<size;c++) {
            var column=new java.util.TreeMap<Integer,Double>();column.put(c,5.0);column.put((c+37)%size,-.7);column.put((c+83)%size,.2);
            for(var entry:column.entrySet()){rows.add(entry.getKey());values.add(entry.getValue());rhs[entry.getKey()]+=entry.getValue()*expected[c];}
            offsets[c+1]=rows.size();
        }
        var matrix=new SparseMatrix(size,offsets,rows.stream().mapToInt(Integer::intValue).toArray(),values.stream().mapToDouble(Double::doubleValue).toArray());
        var result=SparseLuSolver.solveMultiple(matrix,new double[][]{rhs,new double[size]});
        assertArrayEquals(expected,result[0],1e-11);assertArrayEquals(new double[size],result[1]);
    }
    @Test void pivotsAcrossAZeroDiagonalWithoutChangingInputs() {
        int[] columns = {0, 1, 3};
        int[] rows = {1, 0, 1};
        double[] values = {1, 2, 3};
        var matrix = new SparseMatrix(2, columns, rows, values);
        columns[1] = 0;
        rows[0] = 0;
        values[0] = 99;
        double[] rhs = {4, 7};
        assertArrayEquals(new double[] {1, 2}, SparseLuSolver.solve(matrix, rhs), 1e-12);
        assertArrayEquals(new double[] {4, 7}, rhs);
        assertEquals(1, matrix.rowAt(0));
        assertEquals(1, matrix.valueAt(0));
    }

    @Test void equilibratesRowsWithVeryDifferentUnitsAndMagnitudes() {
        var matrix = new SparseMatrix(2, new int[] {0, 2, 4}, new int[] {0, 1, 0, 1},
                new double[] {2e-150, 1e150, 1e-150, 3e150});
        assertArrayEquals(new double[] {2, -1},
                SparseLuSolver.solve(matrix, new double[] {3e-150, -1e150}), 1e-12);
    }

    @Test void rejectsDependentRowsEvenWhenNeitherRowIsEmpty() {
        var matrix = new SparseMatrix(2, new int[] {0, 2, 4}, new int[] {0, 1, 0, 1},
                new double[] {1, 2, 2, 4});
        assertThrows(SparseLuSolver.SolveFailure.class, () -> SparseLuSolver.solve(matrix, new double[] {1, 2}));
    }

    @Test void rejectsAnEmptyRowAndMalformedSparseData() {
        var emptyRow = new SparseMatrix(2, new int[] {0, 1, 1}, new int[] {0}, new double[] {1});
        assertThrows(SparseLuSolver.SolveFailure.class, () -> SparseLuSolver.solve(emptyRow, new double[] {1, 0}));
        assertThrows(IllegalArgumentException.class,
                () -> new SparseMatrix(1, new int[] {0, 2}, new int[] {0, 0}, new double[] {1, 2}));
        assertThrows(IllegalArgumentException.class,
                () -> new SparseMatrix(1, new int[] {0, 1}, new int[] {0}, new double[] {Double.NaN}));
    }

    @Test void handlesEmptySystemAndRejectsInvalidRightHandSide() {
        var empty = new SparseMatrix(0, new int[] {0}, new int[0], new double[0]);
        assertEquals(0, SparseLuSolver.solve(empty, new double[0]).length);
        var one = new SparseMatrix(1, new int[] {0, 1}, new int[] {0}, new double[] {1});
        assertThrows(IllegalArgumentException.class, () -> SparseLuSolver.solve(one, new double[0]));
        assertThrows(IllegalArgumentException.class, () -> SparseLuSolver.solve(one, new double[] {Double.POSITIVE_INFINITY}));
    }
}
