// DO NOT EDIT THIS CLASS, AUTOMATICALLY GENERATED BY io.deephaven.replicators.ReplicateTypedHashers
// Copyright (c) 2016-2022 Deephaven Data Labs and Patent Pending
//
package io.deephaven.engine.table.impl.by.typed.incagg.gen;

import io.deephaven.chunk.ChunkType;
import io.deephaven.engine.table.ColumnSource;
import io.deephaven.engine.table.impl.by.IncrementalChunkedOperatorAggregationStateManagerTypedBase;
import java.util.Arrays;

/**
 * The TypedHashDispatcher returns a pre-generated and precompiled hasher instance suitable for the provided column sources, or null if there is not a precompiled hasher suitable for the specified sources. */
public class TypedHashDispatcher {
    private TypedHashDispatcher() {
        // static use only
    }

    public static IncrementalChunkedOperatorAggregationStateManagerTypedBase dispatch(ColumnSource[] tableKeySources,
            int tableSize, double maximumLoadFactor, double targetLoadFactor) {
        final ChunkType[] chunkTypes = Arrays.stream(tableKeySources).map(ColumnSource::getChunkType).toArray(ChunkType[]::new);;
        if (chunkTypes.length == 1) {
            return dispatchSingle(chunkTypes[0], tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
        }
        if (chunkTypes.length == 2) {
            return dispatchDouble(chunkTypes[0], chunkTypes[1], tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
        }
        return null;
    }

    private static IncrementalChunkedOperatorAggregationStateManagerTypedBase dispatchSingle(ChunkType chunkType,
            ColumnSource[] tableKeySources, int tableSize, double maximumLoadFactor,
            double targetLoadFactor) {
        switch (chunkType) {
            default: throw new UnsupportedOperationException("Invalid chunk type for typed hashers: " + chunkType);
            case Char: return new IncrementalAggHasherChar(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
            case Byte: return new IncrementalAggHasherByte(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
            case Short: return new IncrementalAggHasherShort(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
            case Int: return new IncrementalAggHasherInt(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
            case Long: return new IncrementalAggHasherLong(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
            case Float: return new IncrementalAggHasherFloat(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
            case Double: return new IncrementalAggHasherDouble(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
            case Object: return new IncrementalAggHasherObject(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
        }
    }

    private static IncrementalChunkedOperatorAggregationStateManagerTypedBase dispatchDouble(ChunkType chunkType0,
            ChunkType chunkType1, ColumnSource[] tableKeySources, int tableSize,
            double maximumLoadFactor, double targetLoadFactor) {
        switch (chunkType0) {
            default: throw new UnsupportedOperationException("Invalid chunk type for typed hashers: " + chunkType0);
            case Char:switch (chunkType1) {
                default: throw new UnsupportedOperationException("Invalid chunk type for typed hashers: " + chunkType1);
                case Char: return new IncrementalAggHasherCharChar(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Byte: return new IncrementalAggHasherCharByte(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Short: return new IncrementalAggHasherCharShort(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Int: return new IncrementalAggHasherCharInt(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Long: return new IncrementalAggHasherCharLong(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Float: return new IncrementalAggHasherCharFloat(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Double: return new IncrementalAggHasherCharDouble(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Object: return new IncrementalAggHasherCharObject(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
            }
            case Byte:switch (chunkType1) {
                default: throw new UnsupportedOperationException("Invalid chunk type for typed hashers: " + chunkType1);
                case Char: return new IncrementalAggHasherByteChar(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Byte: return new IncrementalAggHasherByteByte(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Short: return new IncrementalAggHasherByteShort(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Int: return new IncrementalAggHasherByteInt(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Long: return new IncrementalAggHasherByteLong(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Float: return new IncrementalAggHasherByteFloat(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Double: return new IncrementalAggHasherByteDouble(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Object: return new IncrementalAggHasherByteObject(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
            }
            case Short:switch (chunkType1) {
                default: throw new UnsupportedOperationException("Invalid chunk type for typed hashers: " + chunkType1);
                case Char: return new IncrementalAggHasherShortChar(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Byte: return new IncrementalAggHasherShortByte(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Short: return new IncrementalAggHasherShortShort(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Int: return new IncrementalAggHasherShortInt(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Long: return new IncrementalAggHasherShortLong(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Float: return new IncrementalAggHasherShortFloat(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Double: return new IncrementalAggHasherShortDouble(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Object: return new IncrementalAggHasherShortObject(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
            }
            case Int:switch (chunkType1) {
                default: throw new UnsupportedOperationException("Invalid chunk type for typed hashers: " + chunkType1);
                case Char: return new IncrementalAggHasherIntChar(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Byte: return new IncrementalAggHasherIntByte(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Short: return new IncrementalAggHasherIntShort(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Int: return new IncrementalAggHasherIntInt(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Long: return new IncrementalAggHasherIntLong(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Float: return new IncrementalAggHasherIntFloat(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Double: return new IncrementalAggHasherIntDouble(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Object: return new IncrementalAggHasherIntObject(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
            }
            case Long:switch (chunkType1) {
                default: throw new UnsupportedOperationException("Invalid chunk type for typed hashers: " + chunkType1);
                case Char: return new IncrementalAggHasherLongChar(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Byte: return new IncrementalAggHasherLongByte(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Short: return new IncrementalAggHasherLongShort(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Int: return new IncrementalAggHasherLongInt(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Long: return new IncrementalAggHasherLongLong(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Float: return new IncrementalAggHasherLongFloat(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Double: return new IncrementalAggHasherLongDouble(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Object: return new IncrementalAggHasherLongObject(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
            }
            case Float:switch (chunkType1) {
                default: throw new UnsupportedOperationException("Invalid chunk type for typed hashers: " + chunkType1);
                case Char: return new IncrementalAggHasherFloatChar(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Byte: return new IncrementalAggHasherFloatByte(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Short: return new IncrementalAggHasherFloatShort(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Int: return new IncrementalAggHasherFloatInt(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Long: return new IncrementalAggHasherFloatLong(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Float: return new IncrementalAggHasherFloatFloat(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Double: return new IncrementalAggHasherFloatDouble(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Object: return new IncrementalAggHasherFloatObject(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
            }
            case Double:switch (chunkType1) {
                default: throw new UnsupportedOperationException("Invalid chunk type for typed hashers: " + chunkType1);
                case Char: return new IncrementalAggHasherDoubleChar(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Byte: return new IncrementalAggHasherDoubleByte(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Short: return new IncrementalAggHasherDoubleShort(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Int: return new IncrementalAggHasherDoubleInt(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Long: return new IncrementalAggHasherDoubleLong(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Float: return new IncrementalAggHasherDoubleFloat(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Double: return new IncrementalAggHasherDoubleDouble(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Object: return new IncrementalAggHasherDoubleObject(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
            }
            case Object:switch (chunkType1) {
                default: throw new UnsupportedOperationException("Invalid chunk type for typed hashers: " + chunkType1);
                case Char: return new IncrementalAggHasherObjectChar(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Byte: return new IncrementalAggHasherObjectByte(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Short: return new IncrementalAggHasherObjectShort(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Int: return new IncrementalAggHasherObjectInt(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Long: return new IncrementalAggHasherObjectLong(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Float: return new IncrementalAggHasherObjectFloat(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Double: return new IncrementalAggHasherObjectDouble(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
                case Object: return new IncrementalAggHasherObjectObject(tableKeySources, tableSize, maximumLoadFactor, targetLoadFactor);
            }
        }
    }
}
