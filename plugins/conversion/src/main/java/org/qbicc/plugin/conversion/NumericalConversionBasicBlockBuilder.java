package org.qbicc.plugin.conversion;

import java.util.List;

import org.qbicc.context.ClassContext;
import org.qbicc.context.CompilationContext;
import org.qbicc.graph.BasicBlockBuilder;
import org.qbicc.graph.DelegatingBasicBlockBuilder;
import org.qbicc.graph.Value;
import org.qbicc.graph.PointerValue;
import org.qbicc.graph.literal.IntegerLiteral;
import org.qbicc.type.BooleanType;
import org.qbicc.type.FloatType;
import org.qbicc.type.IntegerType;
import org.qbicc.type.PointerType;
import org.qbicc.type.ReferenceType;
import org.qbicc.type.SignedIntegerType;
import org.qbicc.type.TypeType;
import org.qbicc.type.UnsignedIntegerType;
import org.qbicc.type.ValueType;
import org.qbicc.type.WordType;
import org.qbicc.type.definition.LoadedTypeDefinition;

/**
 * This builder fixes up mismatched numerical conversions in order to avoid duplicating this kind of logic in other
 * builders.
 */
public class NumericalConversionBasicBlockBuilder extends DelegatingBasicBlockBuilder {
    private final CompilationContext ctxt;

    public NumericalConversionBasicBlockBuilder(final FactoryContext ctxt, final BasicBlockBuilder delegate) {
        super(delegate);
        this.ctxt = getContext();
    }

    public Value truncate(final Value from, final WordType toType) {
        ValueType fromTypeRaw = from.getType();
        if (fromTypeRaw instanceof WordType) {
            WordType fromType = (WordType) fromTypeRaw;
            if (fromType instanceof SignedIntegerType) {
                if (toType instanceof SignedIntegerType) {
                    if (fromType.getMinBits() > toType.getMinBits()) {
                        // OK
                        return super.truncate(from, toType);
                    } else if (fromType.getMinBits() <= toType.getMinBits()) {
                        // no actual truncation needed
                        return from;
                    }
                } else if (toType instanceof UnsignedIntegerType) {
                    // OK in general but needs to be converted first
                    return truncate(bitCast(from, ((SignedIntegerType) fromType).asUnsigned()), toType);
                } else if (toType instanceof BooleanType) {
                    return super.truncate(from, toType);
                } else if (toType instanceof TypeType) {
                    if (fromType.getMinBits() > toType.getMinBits()) {
                        return super.truncate(from, toType);
                    } else {
                        return from;
                    }
                }
                // otherwise not OK (fall out)
            } else if (fromType instanceof UnsignedIntegerType) {
                if (toType instanceof UnsignedIntegerType) {
                    if (fromType.getMinBits() > toType.getMinBits()) {
                        // OK
                        return super.truncate(from, toType);
                    } else if (fromType.getMinBits() <= toType.getMinBits()) {
                        // no actual truncation needed
                        return from;
                    }
                } else if (toType instanceof SignedIntegerType) {
                    // OK in general but needs to be converted first
                    return truncate(bitCast(from, ((UnsignedIntegerType) fromType).asSigned()), toType);
                } else if (toType instanceof BooleanType) {
                    return super.truncate(from, toType);
                }
                // otherwise not OK (fall out)
            } else if (fromType instanceof FloatType) {
                if (toType instanceof FloatType) {
                    if (fromType.getMinBits() > toType.getMinBits()) {
                        // OK
                        return super.truncate(from, toType);
                    } else if (fromType.getMinBits() <= toType.getMinBits()) {
                        // no actual truncation needed
                        return from;
                    }
                }
            } else if (fromType instanceof BooleanType) {
                if (toType instanceof BooleanType) {
                    // no actual truncation needed
                    return from;
                }
            }
        }
        // report the error but produce the node anyway and continue
        ctxt.error(getLocation(), "Invalid truncation of %s to %s", fromTypeRaw, toType);
        return super.truncate(from, toType);
    }

    public Value extend(final Value from, final WordType toType) {
        ValueType fromTypeRaw = from.getType();
        if (fromTypeRaw instanceof WordType) {
            WordType fromType = (WordType) fromTypeRaw;
            if (fromType instanceof SignedIntegerType) {
                if (toType instanceof SignedIntegerType) {
                    if (fromType.getMinBits() < toType.getMinBits()) {
                        // OK
                        return super.extend(from, toType);
                    } else if (fromType.getMinBits() >= toType.getMinBits()) {
                        // no actual extension needed
                        return from;
                    }
                } else if (toType instanceof UnsignedIntegerType) {
                    // not OK specifically
                    ctxt.error(getLocation(),
                        "Cannot extend a signed integer of type %s into a wider unsigned integer of type %s:"
                            + " either sign-extend to %s first or cast to %s first",
                        fromType, toType, ((UnsignedIntegerType) toType).asSigned(), ((SignedIntegerType) fromType).asUnsigned());
                    return super.extend(from, toType);
                }
                // otherwise not OK (fall out)
            } else if ((fromType instanceof UnsignedIntegerType) || (fromType instanceof BooleanType)) {
                if (toType instanceof UnsignedIntegerType) {
                    if (fromType.getMinBits() < toType.getMinBits() || fromType instanceof BooleanType) {
                        // OK
                        return super.extend(from, toType);
                    } else if (fromType.getMinBits() >= toType.getMinBits()) {
                        // no actual extension needed
                        return from;
                    }
                } else if (toType instanceof SignedIntegerType) {
                    // OK in general but needs to be zero-extended first
                    return bitCast(super.extend(from, ((SignedIntegerType) toType).asUnsigned()), toType);
                }
                // otherwise not OK (fall out)
            } else if (fromType instanceof FloatType) {
                if (toType instanceof FloatType) {
                    if (fromType.getMinBits() < toType.getMinBits()) {
                        // OK
                        return super.extend(from, toType);
                    } else if (fromType.getMinBits() >= toType.getMinBits()) {
                        // no actual extension needed
                        return from;
                    }
                }
            } else if (fromType instanceof TypeType) {
                if (toType instanceof IntegerType) {
                    if (fromType.getMinBits() < toType.getMinBits()) {
                        return super.extend(from, toType);
                    } else if (fromType.getMinBits() >= toType.getMinBits()) {
                        // no actual extension needed
                        return from;
                    }
                }
            }
        }
        // report the error but produce the node anyway and continue
        ctxt.error(getLocation(), "Invalid extension of %s to %s", fromTypeRaw, toType);
        return super.extend(from, toType);
    }

    public Value bitCast(final Value from, final WordType toType) {
        ValueType fromTypeRaw = from.getType();
        if (fromTypeRaw.equals(toType)) {
            // no bitcast needed
            return from;
        }
        if (from instanceof IntegerLiteral && ((IntegerLiteral) from).isZero()) {
            return ctxt.getLiteralFactory().zeroInitializerLiteralOfType(toType);
        }
        if (fromTypeRaw instanceof WordType) {
            WordType fromType = (WordType) fromTypeRaw;
            if (fromType.getMinBits() == toType.getMinBits()) {
                // OK
                return super.bitCast(from, toType);
            }
        }
        ctxt.error(getLocation(), "Invalid bitcast from %s to %s", fromTypeRaw, toType);
        return super.bitCast(from, toType);
    }

    public Value valueConvert(final Value from, final WordType toTypeRaw) {
        BasicBlockBuilder fb = getFirstBuilder();
        ValueType fromTypeRaw = from.getType();
        if (fromTypeRaw instanceof FloatType fromType) {
            if (toTypeRaw instanceof IntegerType toType) {
                ClassContext bcc = ctxt.getBootstrapClassContext();
                LoadedTypeDefinition cNative = bcc.findDefinedType("org/qbicc/runtime/CNative").load();
                if (fromType.getMinBits() == 32) {
                    if (toType.getMinBits() == 32) {
                        PointerValue floatToInt = fb.staticMethod(cNative.requireSingleMethod(me -> me.nameEquals("floatToInt")));
                        return fb.callNoSideEffects(floatToInt, List.of(from));
                    } else if (toType.getMinBits() == 64) {
                        PointerValue floatToLong = fb.staticMethod(cNative.requireSingleMethod(me -> me.nameEquals("floatToLong")));
                        return fb.callNoSideEffects(floatToLong, List.of(from));
                    }
                } else if (fromType.getMinBits() == 64) {
                    if (toType.getMinBits() == 32) {
                        PointerValue doubleToInt = fb.staticMethod(cNative.requireSingleMethod(me -> me.nameEquals("doubleToInt")));
                        return fb.callNoSideEffects(doubleToInt, List.of(from));
                    } else if (toType.getMinBits() == 64) {
                        PointerValue doubleToLong = fb.staticMethod(cNative.requireSingleMethod(me -> me.nameEquals("doubleToLong")));
                        return fb.callNoSideEffects(doubleToLong, List.of(from));
                    }
                }
                ctxt.error(getLocation(), "Unsupported floating-point conversion from %s to %s", fromTypeRaw, toTypeRaw);
                return ctxt.getLiteralFactory().zeroInitializerLiteralOfType(toTypeRaw);
            }
        } else if (fromTypeRaw instanceof IntegerType) {
            if (toTypeRaw instanceof FloatType) {
                // no bounds check needed in this case
                return super.valueConvert(from, toTypeRaw);
            } else if (toTypeRaw instanceof PointerType) {
                // pointer conversions are allowed
                if (fromTypeRaw.getSize() < toTypeRaw.getSize()) {
                    ctxt.error(getLocation(), "Invalid pointer conversion from narrower type %s", fromTypeRaw);
                }
                return super.valueConvert(from, toTypeRaw);
            }
        } else if (fromTypeRaw instanceof PointerType) {
            if (toTypeRaw instanceof IntegerType) {
                if (fromTypeRaw.getSize() > toTypeRaw.getSize()) {
                    ctxt.error(getLocation(), "Invalid pointer conversion to narrower type %s", fromTypeRaw);
                }
                return super.valueConvert(from, toTypeRaw);
            } else if (toTypeRaw instanceof ReferenceType) {
                return super.valueConvert(from, toTypeRaw);
            }
        } else if (fromTypeRaw instanceof ReferenceType) {
            if (toTypeRaw instanceof PointerType) {
                return super.valueConvert(from, toTypeRaw);
            }
        } else if (fromTypeRaw instanceof TypeType) {
            if (toTypeRaw instanceof IntegerType) {
                if (fromTypeRaw.getSize() > toTypeRaw.getSize()) {
                    ctxt.error(getLocation(), "Invalid typeid conversion to narrower type %s", fromTypeRaw);
                } else if (fromTypeRaw.getSize() < toTypeRaw.getSize()) {
                    return extend(from, toTypeRaw);
                }
                return super.valueConvert(from, toTypeRaw);
            }
        }
        ctxt.error(getLocation(), "Invalid conversion from %s to %s", fromTypeRaw, toTypeRaw);
        return super.valueConvert(from, toTypeRaw);
    }
}
