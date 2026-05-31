package net.vulkanmod.vulkshade.effects;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.shader.SPIRVUtils.*;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkshade.shader.ComputePipeline;
import net.vulkanmod.vulkshade.shader.FallbackShader;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Random;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;

public class SSAOEffect {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-SSAO");

    private static final int KERNEL_SIZE = 64;
    private static final int NOISE_SIZE = 4;

    private boolean enabled = true;
    private int radius = 2;
    private float bias = 0.025f;
    private int sampleCount = 16;
    private boolean blurEnabled = true;

    private VulkanImage ssaoTexture;
    private VulkanImage blurTexture;
    private VulkanImage noiseTexture;
    private Buffer kernelBuffer;

    private ComputePipeline ssaoPipeline;
    private ComputePipeline blurPipelineH;
    private ComputePipeline blurPipelineV;

    private int width;
    private int height;
    private int ssaoWidth;
    private int ssaoHeight;

    public SSAOEffect() {
    }

    public void initialize(int width, int height) {
        this.width = width;
        this.height = height;
        this.ssaoWidth = Math.max(1, width / 2);
        this.ssaoHeight = Math.max(1, height / 2);

        createTextures();
        createKernel();
        createPipelines();
    }

    public void render(VkCommandBuffer cmdBuffer, VulkanImage depthTexture, VulkanImage normalTexture,
                       Framebuffer outputFramebuffer) {
        if (!enabled) return;

        try (MemoryStack stack = stackPush()) {
            ssaoPipeline.dispatch(cmdBuffer,
                (ssaoWidth + 15) / 16,
                (ssaoHeight + 15) / 16, 1);

            if (blurEnabled) {
                blurPipelineH.dispatch(cmdBuffer,
                    (ssaoWidth + 15) / 16,
                    (ssaoHeight + 15) / 16, 1);
                blurPipelineV.dispatch(cmdBuffer,
                    (ssaoWidth + 15) / 16,
                    (ssaoHeight + 15) / 16, 1);
            }
        }
    }

    public void resize(int newWidth, int newHeight) {
        cleanupTextures();
        initialize(newWidth, newHeight);
    }

    public void cleanup() {
        cleanupTextures();
        if (kernelBuffer != null) {
            kernelBuffer.scheduleFree();
            kernelBuffer = null;
        }
        if (ssaoPipeline != null) ssaoPipeline.destroy();
        if (blurPipelineH != null) blurPipelineH.destroy();
        if (blurPipelineV != null) blurPipelineV.destroy();
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }
    public void setRadius(int radius) { this.radius = radius; }
    public void setBias(float bias) { this.bias = bias; }
    public void setSampleCount(int count) { this.sampleCount = Math.min(count, KERNEL_SIZE); }
    public void setBlurEnabled(boolean blur) { this.blurEnabled = blur; }
    public VulkanImage getSSAOTexture() { return ssaoTexture; }

    private void createTextures() {
        ssaoTexture = createStorageTexture(ssaoWidth, ssaoHeight, VK_FORMAT_R8_UNORM);
        blurTexture = createStorageTexture(ssaoWidth, ssaoHeight, VK_FORMAT_R8_UNORM);
        noiseTexture = createNoiseTexture();
    }

    private VulkanImage createStorageTexture(int w, int h, int format) {
        return VulkanImage.builder(w, h)
            .setName("SSAO Storage")
            .setFormat(format)
            .setUsage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
            .setLinearFiltering(false).setClamp(true)
            .createVulkanImage();
    }

    private VulkanImage createNoiseTexture() {
        int noiseSize = NOISE_SIZE;
        ByteBuffer noiseData = ByteBuffer.allocateDirect(noiseSize * noiseSize * 4 * 4);
        Random random = new Random(12345);
        for (int i = 0; i < noiseSize * noiseSize; i++) {
            float x = random.nextFloat() * 2.0f - 1.0f;
            float y = random.nextFloat() * 2.0f - 1.0f;
            float z = random.nextFloat() * 2.0f - 1.0f;
            float len = (float) Math.sqrt(x * x + y * y + z * z);
            if (len > 0.001f) { x /= len; y /= len; z /= len; }
            noiseData.putFloat(x).putFloat(y).putFloat(z).putFloat(0.0f);
        }
        noiseData.flip();

        VulkanImage noise = VulkanImage.builder(noiseSize, noiseSize)
            .setName("SSAO Noise")
            .setFormat(VK_FORMAT_R32G32B32A32_SFLOAT)
            .setUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
            .setLinearFiltering(false).setClamp(true)
            .createVulkanImage();
        noise.uploadSubTextureAsync(0, 0, noiseSize, noiseSize, 0, 0, 0, 0, 0, noiseData);
        return noise;
    }

    private void createKernel() {
        int sampleCount = Math.min(this.sampleCount, KERNEL_SIZE);
        ByteBuffer kernelData = ByteBuffer.allocateDirect(sampleCount * 4 * 4);
        Random random = new Random(67890);
        for (int i = 0; i < sampleCount; i++) {
            float x = random.nextFloat() * 2.0f - 1.0f;
            float y = random.nextFloat() * 2.0f - 1.0f;
            float z = random.nextFloat() * 0.5f + 0.5f;
            float len = (float) Math.sqrt(x * x + y * y + z * z);
            if (len > 0.001f) { x /= len; y /= len; z /= len; }
            float scale = (float) i / sampleCount;
            scale = 0.1f + 0.9f * scale * scale;
            kernelData.putFloat(x * scale).putFloat(y * scale).putFloat(z * scale).putFloat(0.0f);
        }
        kernelData.flip();

        int bufferSize = kernelData.remaining();
        this.kernelBuffer = new Buffer(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, MemoryTypes.GPU_MEM);
        this.kernelBuffer.createBuffer(bufferSize);
        this.kernelBuffer.copyBuffer(kernelData, bufferSize);
    }

    private void createPipelines() {
        String computeSource = generateSSAOKernelSource();
        ssaoPipeline = new ComputePipeline("ssao_main");
        ssaoPipeline.compileFromSource(computeSource);
        ssaoPipeline.create();
    }

    private String generateSSAOKernelSource() {
        return String.format("""
            #version 450
            layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;

            layout(binding = 0) uniform sampler2D depthTex;
            layout(binding = 1) uniform sampler2D normalTex;
            layout(binding = 2, r8) uniform writeonly image2D ssaoOutput;
            layout(binding = 3) uniform sampler2D noiseTex;

            layout(binding = 4) uniform SSAOData {
                vec4 samples[%d];
                mat4 projection;
                ivec2 screenSize;
                float radius;
                float bias;
                int sampleCount;
            };

            void main() {
                ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
                ivec2 size = ivec2(gl_NumWorkGroups.xy * gl_WorkGroupSize.xy);
                if (coord.x >= size.x || coord.y >= size.y) return;

                vec2 texCoord = (vec2(coord) + 0.5) / vec2(size);
                float depth = texture(depthTex, texCoord).r;
                if (depth >= 1.0) {
                    imageStore(ssaoOutput, coord, vec4(1.0));
                    return;
                }

                vec3 normal = texture(normalTex, texCoord).xyz * 2.0 - 1.0;
                vec3 noise = texture(noiseTex, texCoord * vec2(size) / %d.0).xyz;
                vec3 tangent = normalize(noise - normal * dot(noise, normal));
                vec3 bitangent = cross(normal, tangent);
                mat3 TBN = mat3(tangent, bitangent, normal);

                float occlusion = 0.0;
                for (int i = 0; i < sampleCount; i++) {
                    vec3 samplePos = TBN * samples[i].xyz;
                    samplePos = samplePos * radius + vec3(texCoord, depth);

                    vec4 offset = projection * vec4(samplePos, 1.0);
                    vec3 projCoord = offset.xyz / offset.w;
                    projCoord = projCoord * 0.5 + 0.5;

                    float sampleDepth = texture(depthTex, projCoord.xy).r;
                    float rangeCheck = smoothstep(0.0, 1.0, radius / abs(depth - sampleDepth));
                    occlusion += (sampleDepth >= projCoord.z + bias ? 1.0 : 0.0) * rangeCheck;
                }

                float ao = 1.0 - (occlusion / sampleCount);
                imageStore(ssaoOutput, coord, vec4(ao));
            }
            """, KERNEL_SIZE, NOISE_SIZE);
    }

    private void cleanupTextures() {
        if (ssaoTexture != null) { ssaoTexture.free(); ssaoTexture = null; }
        if (blurTexture != null) { blurTexture.free(); blurTexture = null; }
        if (noiseTexture != null) { noiseTexture.free(); noiseTexture = null; }
    }
}
