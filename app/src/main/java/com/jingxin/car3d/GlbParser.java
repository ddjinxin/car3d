package com.jingxin.car3d;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 纯 Java GLB 解析器
 * 从 GLB 二进制文件中提取：顶点位置、UV 坐标、索引、纹理图片
 */
public class GlbParser {

    private static final String TAG = "GlbParser";

    // 解析结果
    public FloatBuffer vertexBuffer;  // 每顶点: position(3) + uv(2) = 5 floats
    public int vertexCount;
    public Bitmap baseColorBitmap;
    public float[] boundsMin;
    public float[] boundsMax;

    /**
     * 从 InputStream 解析 GLB 文件
     */
    public boolean parse(InputStream is) {
        try {
            byte[] glbData = readAll(is);
            return parse(glbData);
        } catch (Exception e) {
            Log.e(TAG, "GLB 解析失败", e);
            return false;
        }
    }

    /**
     * 从字节数组解析 GLB 文件
     */
    public boolean parse(byte[] glbData) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(glbData).order(ByteOrder.LITTLE_ENDIAN);

            // 1. 验证 GLB 头
            int magic = bb.getInt();
            int version = bb.getInt();
            int totalLength = bb.getInt();
            if (magic != 0x46546C67) { // "glTF" little-endian
                Log.e(TAG, "不是有效的 GLB 文件, magic=" + magic);
                return false;
            }
            Log.d(TAG, "GLB header: version=" + version + ", length=" + totalLength);

            // 2. 读取 chunks
            String jsonStr = null;
            byte[] binaryData = null;

            while (bb.remaining() >= 8) {
                int chunkLength = bb.getInt();
                int chunkType = bb.getInt();

                byte[] chunkData = new byte[chunkLength];
                bb.get(chunkData);

                if (chunkType == 0x4E4F534A) { // "JSON"
                    jsonStr = new String(chunkData, "UTF-8");
                } else if (chunkType == 0x004E4942) { // "BIN\0"
                    binaryData = chunkData;
                }
            }

            if (jsonStr == null || binaryData == null) {
                Log.e(TAG, "缺少 JSON 或 BIN chunk");
                return false;
            }

            // 3. 解析 JSON
            JSONObject gltf = new JSONObject(jsonStr);
            JSONArray accessors = gltf.getJSONArray("accessors");
            JSONArray bufferViews = gltf.getJSONArray("bufferViews");

            // 4. 找到 mesh primitive
            JSONObject mesh = gltf.getJSONArray("meshes").getJSONObject(0);
            JSONObject prim = mesh.getJSONArray("primitives").getJSONObject(0);
            JSONObject attributes = prim.getJSONObject("attributes");

            int positionAccessorIdx = attributes.getInt("POSITION");
            int uvAccessorIdx = attributes.optInt("TEXCOORD_0", -1);
            int indicesAccessorIdx = prim.optInt("indices", -1);

            // 5. 获取 accessor 数据
            Accessor posAccessor = readAccessor(accessors.getJSONObject(positionAccessorIdx), bufferViews, binaryData);
            Accessor uvAccessor = (uvAccessorIdx >= 0) ?
                    readAccessor(accessors.getJSONObject(uvAccessorIdx), bufferViews, binaryData) : null;
            Accessor idxAccessor = (indicesAccessorIdx >= 0) ?
                    readAccessor(accessors.getJSONObject(indicesAccessorIdx), bufferViews, binaryData) : null;

            boundsMin = jsonArrayToFloatArray(posAccessor.min);
            boundsMax = jsonArrayToFloatArray(posAccessor.max);

            Log.d(TAG, "POSITION: " + posAccessor.count + " verts, componentType=" + posAccessor.componentType);
            if (uvAccessor != null) {
                Log.d(TAG, "TEXCOORD_0: " + uvAccessor.count + " verts");
            }
            if (idxAccessor != null) {
                Log.d(TAG, "INDICES: " + idxAccessor.count + ", componentType=" + idxAccessor.componentType);
            }

            // 6. 展开顶点（如果有索引，按索引展开三角形）
            if (idxAccessor != null) {
                vertexCount = idxAccessor.count;
                vertexBuffer = expandWithIndices(posAccessor, uvAccessor, idxAccessor);
            } else {
                vertexCount = posAccessor.count;
                vertexBuffer = expandNoIndices(posAccessor, uvAccessor);
            }

            Log.d(TAG, "展开后顶点: " + vertexCount + ", buffer: " + (vertexCount * 5 * 4 / 1024) + " KB");

            // 7. 提取 baseColor 纹理
            int materialIdx = prim.optInt("material", 0);
            if (materialIdx < gltf.getJSONArray("materials").length()) {
                JSONObject material = gltf.getJSONArray("materials").getJSONObject(materialIdx);
                JSONObject pbr = material.optJSONObject("pbrMetallicRoughness");
                if (pbr != null) {
                    JSONObject texInfo = pbr.optJSONObject("baseColorTexture");
                    if (texInfo != null) {
                        int texIdx = texInfo.getInt("index");
                        JSONObject texture = gltf.getJSONArray("textures").getJSONObject(texIdx);
                        int imageIdx = texture.getInt("source");
                        JSONObject image = gltf.getJSONArray("images").getJSONObject(imageIdx);
                        int bvIdx = image.getInt("bufferView");
                        JSONObject bv = bufferViews.getJSONObject(bvIdx);
                        int imgOffset = bv.optInt("byteOffset", 0);
                        int imgLength = bv.getInt("byteLength");
                        String mimeType = image.optString("mimeType", "image/png");

                        byte[] imgData = new byte[imgLength];
                        System.arraycopy(binaryData, imgOffset, imgData, 0, imgLength);

                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                        baseColorBitmap = BitmapFactory.decodeByteArray(imgData, 0, imgData.length, opts);

                        if (baseColorBitmap != null) {
                            Log.d(TAG, "纹理: " + baseColorBitmap.getWidth() + "x" + baseColorBitmap.getHeight()
                                    + ", mime=" + mimeType + ", size=" + (imgLength / 1024) + "KB");
                        } else {
                            Log.e(TAG, "纹理解码失败, mime=" + mimeType + ", dataLen=" + imgLength);
                        }
                    }
                }
            }

            return vertexBuffer != null;
        } catch (Exception e) {
            Log.e(TAG, "GLB 解析异常", e);
            return false;
        }
    }

    // ========== 内部方法 ==========

    private static class Accessor {
        int count;
        int componentType;
        String type;
        float[] data;      // 解析后的 float 数组
        int[] intData;     // 索引用 int 数组
        JSONArray min;
        JSONArray max;
    }

    private Accessor readAccessor(JSONObject acc, JSONArray bufferViews, byte[] binaryData) throws Exception {
        Accessor result = new Accessor();
        result.count = acc.getInt("count");
        result.componentType = acc.getInt("componentType");
        result.type = acc.getString("type");
        result.min = acc.optJSONArray("min");
        result.max = acc.optJSONArray("max");

        int bvIdx = acc.getInt("bufferView");
        int accByteOffset = acc.optInt("byteOffset", 0);
        JSONObject bv = bufferViews.getJSONObject(bvIdx);
        int bvByteOffset = bv.optInt("byteOffset", 0);
        int bvByteLength = bv.getInt("byteLength");

        int totalOffset = bvByteOffset + accByteOffset;

        if ("SCALAR".equals(result.type)) {
            // 索引数据，按 componentType 读取为 int
            int elemSize = getComponentSize(result.componentType);
            int idxCount = bvByteLength / elemSize;
            result.intData = new int[idxCount];
            ByteBuffer src = ByteBuffer.wrap(binaryData, totalOffset, bvByteLength).order(ByteOrder.LITTLE_ENDIAN);

            switch (result.componentType) {
                case 5121: // UNSIGNED_BYTE
                    for (int i = 0; i < idxCount; i++) result.intData[i] = src.get() & 0xFF;
                    break;
                case 5123: // UNSIGNED_SHORT
                    for (int i = 0; i < idxCount; i++) result.intData[i] = src.getShort() & 0xFFFF;
                    break;
                case 5125: // UNSIGNED_INT
                    for (int i = 0; i < idxCount; i++) result.intData[i] = src.getInt();
                    break;
            }
        } else {
            // 顶点属性数据，统一转为 float
            int numComponents = getTypeComponents(result.type);
            int elemCount = bvByteLength / getComponentSize(result.componentType);
            result.data = new float[elemCount];
            ByteBuffer src = ByteBuffer.wrap(binaryData, totalOffset, bvByteLength).order(ByteOrder.LITTLE_ENDIAN);

            switch (result.componentType) {
                case 5120: // BYTE
                    for (int i = 0; i < elemCount; i++) result.data[i] = src.get();
                    break;
                case 5121: // UNSIGNED_BYTE
                    for (int i = 0; i < elemCount; i++) result.data[i] = (src.get() & 0xFF);
                    break;
                case 5122: // SHORT
                    for (int i = 0; i < elemCount; i++) result.data[i] = src.getShort();
                    break;
                case 5123: // UNSIGNED_SHORT
                    for (int i = 0; i < elemCount; i++) result.data[i] = (src.getShort() & 0xFFFF);
                    break;
                case 5126: // FLOAT
                    for (int i = 0; i < elemCount; i++) result.data[i] = src.getFloat();
                    break;
            }
        }

        return result;
    }

    /**
     * 带索引：按索引展开三角形，输出 position(3) + uv(2)
     */
    private FloatBuffer expandWithIndices(Accessor pos, Accessor uv, Accessor idx) {
        int floatsPerVert = 5; // pos(3) + uv(2)
        float[] expanded = new float[idx.count * floatsPerVert];
        int posComponents = getTypeComponents(pos.type);
        int uvComponents = (uv != null) ? getTypeComponents(uv.type) : 0;

        for (int i = 0; i < idx.count; i++) {
            int vertIdx = idx.intData[i];
            int base = i * floatsPerVert;

            // Position
            for (int c = 0; c < 3; c++) {
                expanded[base + c] = pos.data[vertIdx * posComponents + c];
            }

            // UV（GLB 中 V=0 在底部，与 OpenGL 一致，无需翻转）
            if (uv != null && uvComponents >= 2) {
                expanded[base + 3] = uv.data[vertIdx * uvComponents + 0];
                expanded[base + 4] = uv.data[vertIdx * uvComponents + 1];
            } else {
                expanded[base + 3] = 0;
                expanded[base + 4] = 0;
            }
        }

        FloatBuffer fb = ByteBuffer.allocateDirect(expanded.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(expanded);
        fb.flip();
        return fb;
    }

    /**
     * 无索引：直接输出 position(3) + uv(2)
     */
    private FloatBuffer expandNoIndices(Accessor pos, Accessor uv) {
        int floatsPerVert = 5;
        int vertCount = pos.count;
        float[] expanded = new float[vertCount * floatsPerVert];
        int posComponents = getTypeComponents(pos.type);
        int uvComponents = (uv != null) ? getTypeComponents(uv.type) : 0;

        for (int i = 0; i < vertCount; i++) {
            int base = i * floatsPerVert;

            for (int c = 0; c < 3; c++) {
                expanded[base + c] = pos.data[i * posComponents + c];
            }

            if (uv != null && uvComponents >= 2) {
                expanded[base + 3] = uv.data[i * uvComponents + 0];
                expanded[base + 4] = uv.data[i * uvComponents + 1];
            } else {
                expanded[base + 3] = 0;
                expanded[base + 4] = 0;
            }
        }

        FloatBuffer fb = ByteBuffer.allocateDirect(expanded.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(expanded);
        fb.flip();
        return fb;
    }

    private static int getComponentSize(int componentType) {
        switch (componentType) {
            case 5120: case 5121: return 1; // BYTE
            case 5122: case 5123: return 2; // SHORT
            case 5125:             return 4; // UNSIGNED_INT
            case 5126:             return 4; // FLOAT
            default: return 4;
        }
    }

    private static int getTypeComponents(String type) {
        switch (type) {
            case "SCALAR": return 1;
            case "VEC2":   return 2;
            case "VEC3":   return 3;
            case "VEC4":   return 4;
            case "MAT2":   return 4;
            case "MAT3":   return 9;
            case "MAT4":   return 16;
            default: return 1;
        }
    }

    private static float[] jsonArrayToFloatArray(JSONArray arr) {
        if (arr == null) return new float[0];
        float[] result = new float[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            result[i] = (float) arr.optDouble(i, 0);
        }
        return result;
    }

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int len;
        while ((len = is.read(tmp)) != -1) buf.write(tmp, 0, len);
        is.close();
        return buf.toByteArray();
    }

    public void release() {
        if (baseColorBitmap != null && !baseColorBitmap.isRecycled()) {
            baseColorBitmap.recycle();
            baseColorBitmap = null;
        }
    }
}
