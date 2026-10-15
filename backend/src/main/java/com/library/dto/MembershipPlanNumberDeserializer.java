package com.library.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;

/** Rule quantities and revisions must never truncate fractional JSON numbers. */
public final class MembershipPlanNumberDeserializer {
    private MembershipPlanNumberDeserializer() {}
    public static class IntegerValue extends StdDeserializer<Integer> {
        public IntegerValue() { super(Integer.class); }
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
                return (Integer) context.handleUnexpectedToken(Integer.class, parser);
            return parser.getIntValue();
        }
    }
    public static class LongValue extends StdDeserializer<Long> {
        public LongValue() { super(Long.class); }
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
                return (Long) context.handleUnexpectedToken(Long.class, parser);
            return parser.getLongValue();
        }
    }
}
