package com.wormzjl.createcheme.client.gui.common;

/** Keeps untouched full-precision input values behind concise text. */
public final class NumericDraft {
    private final double exact;
    private final String initial;
    private String text;
    public NumericDraft(double value){exact=value;initial=value!=0&&Math.abs(value)<1?String.format(java.util.Locale.ROOT,"%.4g",value):ProcessUi.number(value);text=initial;}
    public String text(){return text;}
    public void text(String value){text=value;}
    public double value(){
        double value=text.equals(initial)?exact:Double.parseDouble(text);
        if(!Double.isFinite(value))throw new IllegalArgumentException("Enter a finite number");
        return value;
    }
}
