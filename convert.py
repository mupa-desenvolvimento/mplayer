import tensorflow as tf

print("Loading Keras model with compile=False...")
try:
    model = tf.keras.models.load_model("face_model_v5.h5", compile=False)
    model.summary()

    print("Converting model to TFLite...")
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite_model = converter.convert()

    with open("age_gender_model.tflite", "wb") as f:
        f.write(tflite_model)
    print("Conversion completed! age_gender_model.tflite created successfully.")
except Exception as e:
    print("Error during conversion:", str(e))
