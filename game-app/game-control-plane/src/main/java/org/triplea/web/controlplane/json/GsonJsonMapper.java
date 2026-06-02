package org.triplea.web.controlplane.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import io.javalin.json.JsonMapper;
import java.lang.reflect.Type;
import java.time.Instant;

/**
 * Backs Javalin's request/response (de)serialization with gson, the project's JSON library (rather
 * than Javalin's default Jackson, which isn't a dependency here). Instants are rendered as ISO-8601
 * strings — gson has no built-in java.time support.
 */
public final class GsonJsonMapper implements JsonMapper {

  private final Gson gson =
      new GsonBuilder()
          .registerTypeAdapter(
              Instant.class,
              (JsonSerializer<Instant>) (src, type, ctx) -> new JsonPrimitive(src.toString()))
          .registerTypeAdapter(
              Instant.class,
              (JsonDeserializer<Instant>) (json, type, ctx) -> Instant.parse(json.getAsString()))
          .create();

  @Override
  public String toJsonString(final Object obj, final Type type) {
    return gson.toJson(obj, type);
  }

  @Override
  public <T> T fromJsonString(final String json, final Type targetType) {
    return gson.fromJson(json, targetType);
  }
}
