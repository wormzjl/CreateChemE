package com.wormzjl.createcheme.runtime.fluid;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The binary primitives of a checkpoint storage unit (format 4 onwards): big-endian fixed-width integers and doubles, unsigned
 * LEB128 variable-length integers, UTF-8 texts with their byte length, and sparse double arrays - a bit mask of the
 * entries whose raw bits are not zero, then those entries' raw bits. Every double is written and read with its exact
 * bits and must be finite, as every number of format 3 had to be; a sparse array never stores an entry whose raw bits
 * are zero, so one value has one encoding and a unit re-encodes to the same bytes. The reader refuses a truncated unit,
 * an overlong or out-of-range integer, malformed UTF-8, a non-canonical sparse entry and trailing bytes.
 */
final class FluidUnitIO {
    private FluidUnitIO() {}

    static final class Writer {
        private byte[] buffer=new byte[4096];private int size;
        int size(){return size;}
        byte[] toByteArray(){return Arrays.copyOf(buffer,size);}
        /** Whether the bytes written in [from, to) equal those in [otherFrom, otherTo). */
        boolean sameBytes(int from,int to,int otherFrom,int otherTo){return Arrays.equals(buffer,from,to,buffer,otherFrom,otherTo);}
        /** Copies bytes [from, to) of {@code other}'s buffer here. */
        void append(Writer other,int from,int to){ensure(to-from);System.arraycopy(other.buffer,from,buffer,size,to-from);size+=to-from;}
        void truncate(int length){if(length<0||length>size)throw new IllegalArgumentException();size=length;}
        private void ensure(int more){if(size+more>buffer.length)buffer=Arrays.copyOf(buffer,Math.max(buffer.length*2,size+more));}
        void u8(int value){ensure(1);buffer[size++]=(byte)value;}
        void bool(boolean value){u8(value?1:0);}
        void i32(int value){ensure(4);buffer[size++]=(byte)(value>>>24);buffer[size++]=(byte)(value>>>16);buffer[size++]=(byte)(value>>>8);buffer[size++]=(byte)value;}
        void i64(long value){ensure(8);for(int shift=56;shift>=0;shift-=8)buffer[size++]=(byte)(value>>>shift);}
        /** A finite double with its exact bits. */
        void f64(double value){if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite checkpoint number");i64(Double.doubleToRawLongBits(value));}
        void varint(int value){if(value<0)throw new IllegalArgumentException("Negative checkpoint count");varlong(value);}
        void varlong(long value) {
            if(value<0)throw new IllegalArgumentException("Negative checkpoint count");
            ensure(10);while((value&~0x7FL)!=0){buffer[size++]=(byte)((value&0x7F)|0x80);value>>>=7;}buffer[size++]=(byte)value;
        }
        void text(String value){var bytes=value.getBytes(StandardCharsets.UTF_8);varint(bytes.length);raw(bytes);}
        void raw(byte[] bytes){ensure(bytes.length);System.arraycopy(bytes,0,buffer,size,bytes.length);size+=bytes.length;}
        /** A double array: its length, a mask of the entries whose raw bits are not zero, then those entries. */
        void sparse(double[] values) {
            varint(values.length);int mask=size;int maskBytes=(values.length+7)/8;ensure(maskBytes);Arrays.fill(buffer,size,size+maskBytes,(byte)0);size+=maskBytes;
            for(int i=0;i<values.length;i++) {
                long bits=Double.doubleToRawLongBits(values[i]);if(bits==0)continue;
                if(!Double.isFinite(values[i]))throw new IllegalArgumentException("Nonfinite checkpoint number");
                buffer[mask+i/8]|=(byte)(1<<(i%8));i64(bits);
            }
        }
    }

    static final class Reader {
        private final byte[] data;private final int end;private int position;private final String what;
        Reader(byte[] data,int offset,int length,String what) {
            if(offset<0||length<0||offset+length>data.length)throw new IllegalArgumentException("Invalid saved "+what+": out of bounds");
            this.data=data;this.position=offset;this.end=offset+length;this.what=what;
        }
        IllegalArgumentException invalid(String reason){return new IllegalArgumentException("Invalid saved "+what+": "+reason);}
        private void need(int bytes){if(end-position<bytes)throw invalid("truncated");}
        int u8(){need(1);return data[position++]&0xFF;}
        boolean bool(){int value=u8();if(value>1)throw invalid("invalid flag "+value);return value==1;}
        int i32(){need(4);int value=ByteBuffer.wrap(data,position,4).getInt();position+=4;return value;}
        long i64(){need(8);long value=ByteBuffer.wrap(data,position,8).getLong();position+=8;return value;}
        double f64(){double value=Double.longBitsToDouble(i64());if(!Double.isFinite(value))throw invalid("nonfinite checkpoint number");return value;}
        long varlong() {
            long value=0;
            for(int shift=0;;shift+=7) {
                int b=u8();if(shift==63&&(b&0x7E)!=0)throw invalid("integer overflow");
                value|=(long)(b&0x7F)<<shift;
                if((b&0x80)==0){if(b==0&&shift>0)throw invalid("overlong integer");return value;}
                if(shift>=63)throw invalid("integer overflow");
            }
        }
        /** A count at most {@code maximum}. */
        int varint(int maximum){long value=varlong();if(value>maximum)throw invalid("count "+value+" exceeds "+maximum);return (int)value;}
        String text(int maximumBytes) {
            int length=varint(maximumBytes);need(length);
            try{var text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data,position,length)).toString();position+=length;return text;}
            catch(CharacterCodingException malformed){throw invalid("malformed text");}
        }
        byte[] raw(int length){need(length);var bytes=Arrays.copyOfRange(data,position,position+length);position+=length;return bytes;}
        double[] sparse(int maximumLength) {
            int length=varint(maximumLength);int maskBytes=(length+7)/8;need(maskBytes);int mask=position;position+=maskBytes;
            if(length%8!=0&&(data[mask+maskBytes-1]&0xFF)>>>(length%8)!=0)throw invalid("mask bits beyond the array");
            var values=new double[length];
            for(int i=0;i<length;i++) {
                if((data[mask+i/8]&(1<<(i%8)))==0)continue;
                long bits=i64();if(bits==0)throw invalid("zero entry stored in a sparse array");
                values[i]=Double.longBitsToDouble(bits);if(!Double.isFinite(values[i]))throw invalid("nonfinite checkpoint number");
            }
            return values;
        }
        /** Refuses anything left after the last field. */
        void end(){if(position!=end)throw invalid((end-position)+" trailing bytes");}
    }
}
