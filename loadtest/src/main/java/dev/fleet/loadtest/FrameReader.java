package dev.fleet.loadtest;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.util.concurrent.atomic.LongAdder;

/** Stream timestamps directly into histograms; never construct or retain position/event objects. */
final class FrameReader {
    private static final JsonFactory JSON = new JsonFactory();

    private FrameReader() {}

    static String read(
            String json,
            long receivedAt,
            LatencyHistogram pipeline,
            LatencyHistogram endToEnd,
            LongAdder missingPipeline) {
        try (var parser = JSON.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("Expected a WebSocket object frame");
            }
            String type = "";
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    throw new IllegalArgumentException("Malformed WebSocket frame");
                }
                String field = parser.currentName();
                var value = parser.nextToken();
                if (field.equals("type")) {
                    type = parser.getValueAsString("");
                } else if (field.equals("positions") && value == JsonToken.START_ARRAY) {
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        timestamps(parser, receivedAt, pipeline, endToEnd, missingPipeline);
                    }
                } else if (field.equals("event") && value == JsonToken.START_OBJECT) {
                    timestamps(parser, receivedAt, pipeline, endToEnd, missingPipeline);
                } else {
                    parser.skipChildren();
                }
            }
            return type;
        } catch (IOException error) {
            throw new IllegalArgumentException("Malformed WebSocket frame", error);
        }
    }

    private static void timestamps(
            JsonParser parser,
            long receivedAt,
            LatencyHistogram pipeline,
            LatencyHistogram endToEnd,
            LongAdder missingPipeline)
            throws IOException {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            throw new IllegalArgumentException("Expected a position or event object");
        }
        long ingestedAt = 0;
        long emittedAt = 0;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) {
                throw new IllegalArgumentException("Malformed timestamp object");
            }
            String field = parser.currentName();
            parser.nextToken();
            if (field.equals("ingestedAt") && parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
                ingestedAt = parser.getLongValue();
            } else if (field.equals("emittedAt")
                    && parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
                emittedAt = parser.getLongValue();
            } else {
                parser.skipChildren();
            }
        }
        if (pipeline != null) {
            if (ingestedAt > 0) {
                pipeline.record(receivedAt - ingestedAt);
            } else {
                missingPipeline.increment();
            }
            if (emittedAt > 0) {
                endToEnd.record(receivedAt - emittedAt);
            }
        }
    }
}
