package com.example.securevotingapp;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public class FaceRecognizer {

    private static final int INPUT_SIZE = 112;      // MobileFaceNet expects 112x112 RGB images
    private static final int EMBEDDING_SIZE = 192;  // Outputs 192 unique facial numbers
    private static final float IMAGE_MEAN = 127.5f;
    private static final float IMAGE_STD = 127.5f;

    private final Interpreter interpreter;

    public FaceRecognizer(Context context) throws IOException {
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(4);
        interpreter = new Interpreter(loadModelFile(context, "mobile_face_net.tflite"), options);
    }

    private MappedByteBuffer loadModelFile(Context context, String modelName) throws IOException {
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(modelName);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY,
                fileDescriptor.getStartOffset(),
                fileDescriptor.getDeclaredLength());
    }

    // Converts a cropped face Bitmap into a 192-number embedding array
    public float[] getFaceEmbedding(Bitmap faceBitmap) {
        Bitmap resized = Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true);

        ByteBuffer imgData = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4);
        imgData.order(ByteOrder.nativeOrder());

        int[] intValues = new int[INPUT_SIZE * INPUT_SIZE];
        resized.getPixels(intValues, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);

        for (int pixel : intValues) {
            imgData.putFloat((((pixel >> 16) & 0xFF) - IMAGE_MEAN) / IMAGE_STD); // R
            imgData.putFloat((((pixel >> 8) & 0xFF) - IMAGE_MEAN) / IMAGE_STD);  // G
            imgData.putFloat(((pixel & 0xFF) - IMAGE_MEAN) / IMAGE_STD);         // B
        }

        float[][] output = new float[1][EMBEDDING_SIZE];
        interpreter.run(imgData, output);
        return l2Normalize(output[0]);
    }

    // Normalizes the vector so distance comparisons are accurate
    private float[] l2Normalize(float[] embedding) {
        float sum = 0f;
        for (float v : embedding) sum += v * v;
        float norm = (float) Math.sqrt(sum);
        if (norm == 0f) return embedding;
        for (int i = 0; i < embedding.length; i++) embedding[i] /= norm;
        return embedding;
    }

    // Euclidean distance: < 1.0 usually means same person, >= 1.0 means different person
    public static float calculateDistance(float[] emb1, float[] emb2) {
        float sum = 0f;
        for (int i = 0; i < emb1.length; i++) {
            float diff = emb1[i] - emb2[i];
            sum += diff * diff;
        }
        return (float) Math.sqrt(sum);
    }

    public void close() {
        interpreter.close();
    }
}