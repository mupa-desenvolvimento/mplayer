import tensorflow as tf

interpreter = tf.lite.Interpreter(model_path="age_gender_model.tflite")
interpreter.allocate_tensors()

print("Inputs:")
for idx, details in enumerate(interpreter.get_input_details()):
    print(f"  Input {idx}: Name={details['name']}, Shape={details['shape']}, Type={details['dtype']}")

print("Outputs:")
for idx, details in enumerate(interpreter.get_output_details()):
    print(f"  Output {idx}: Name={details['name']}, Shape={details['shape']}, Type={details['dtype']}")
