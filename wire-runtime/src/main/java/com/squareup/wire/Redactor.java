package com.squareup.wire;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Use Redactor to set any 'redacted' annotated fields in a message to null.  */
public class Redactor<T extends Message> {
  private static final Redactor<Message> NOOP_REDACTOR = new NoOpRedactor<Message>();

  private final Constructor<Message.Builder<T>> builderConstructor;
  private final List<Field> redactedFields;
  private final List<Field> messageFields;
  private final List<Redactor<Message>> messageRedactors;

  protected Redactor(Constructor<Message.Builder<T>> builderConstructor, List<Field> redactedFields,
      List<Field> messageFields, List<Redactor<Message>> messageRedactors) {
    this.builderConstructor = builderConstructor;
    this.redactedFields = redactedFields;
    this.messageFields = messageFields;
    this.messageRedactors = messageRedactors;
  }

  /** Creates a Redactor for a given message type */
  public static <T extends Message> Redactor<T> get(Class<T> messageClass) {
    try {
      Class<?> builderClass = Class.forName(messageClass.getName() + "$Builder");
      List<Field> redactedFields = new ArrayList<Field>();
      List<Field> messageFields = new ArrayList<Field>();
      List<Redactor<Message>> messageRedactors = new ArrayList<Redactor<Message>>();

      for (Field messageField : messageClass.getDeclaredFields()) {
        if (Modifier.isStatic(messageField.getModifiers())) {
          continue;
        }

        // Process fields annotated with '@ProtoField'
        ProtoField annotation = messageField.getAnnotation(ProtoField.class);
        if (annotation != null && annotation.redacted()) {
          redactedFields.add(builderClass.getDeclaredField(messageField.getName()));
        } else if (Message.class.isAssignableFrom(messageField.getType())) {
          // If the field is a Message, it needs its own Redactor.
          Field field = builderClass.getDeclaredField(messageField.getName());
          Redactor fieldRedactor = get((Class) field.getType());

          if (fieldRedactor == NOOP_REDACTOR) continue;
          messageFields.add(field);
          messageRedactors.add(fieldRedactor);
        }
      }

      if (redactedFields.isEmpty() && messageFields.isEmpty()) {
        return (Redactor<T>) NOOP_REDACTOR;
      }

      Constructor<Message.Builder<T>> builderConstructor =
          (Constructor<Message.Builder<T>>) builderClass.getConstructor(messageClass);
      return new Redactor<T>(builderConstructor, redactedFields, messageFields, messageRedactors);
    } catch (Exception e) {
      throw new AssertionError(e.getMessage());
    }

  }

  /** Returns the given message with all of the 'redacted' fields set to null */
  public T redact(T message) {
    if (message == null) return null;

    try {
      Message.Builder<T> builder = builderConstructor.newInstance(message);

      for (Field field : redactedFields) {
        field.set(builder, null);
      }

      for (int i = 0; i < messageFields.size(); i++) {
        Field field = messageFields.get(i);
        Redactor<Message> r = messageRedactors.get(i);
        field.set(builder, r.redact((Message) field.get(builder)));
      }

      return builder.build();
    } catch (Exception e) {
      throw new AssertionError(e.getMessage());
    }
  }

  private static class NoOpRedactor<T extends Message> extends Redactor<T> {

    public NoOpRedactor() {
      super(null, null, null, null);
    }

    @Override
    public T redact(T message) {
      return message;
    }
  }
}
