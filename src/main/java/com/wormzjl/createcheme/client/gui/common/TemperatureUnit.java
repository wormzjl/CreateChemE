package com.wormzjl.createcheme.client.gui.common;

/** Temperatures cross the wire in kelvin; display defaults to Celsius. */
public enum TemperatureUnit {
    CELSIUS("C",273.15), KELVIN("K",0);
    private final String symbol;
    private final double offset;
    TemperatureUnit(String symbol,double offset){this.symbol=symbol;this.offset=offset;}
    public String symbol(){return symbol;}
    public double display(double kelvin){return kelvin-offset;}
    public double kelvin(double displayed){return displayed+offset;}
    public TemperatureUnit next(){return this==CELSIUS?KELVIN:CELSIUS;}
}
