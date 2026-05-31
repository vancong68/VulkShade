package net.vulkanmod.vulkshade.shader;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.SPIRVUtils.SPIRV;
import net.vulkanmod.vulkan.shader.SPIRVUtils.ShaderKind;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.PushConstants;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkshade.optimization.DescriptorPoolManager;
import net.vulkanmod.vulkshade.shader.ShaderManager.ShaderEntry;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class ComputePipeline {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-ComputePipeline");
    private static final VkDevice DEVICE = Vulkan.getVkDevice();

    private final String name;
    private SPIRV computeSPIRV;
    private long shaderModule;
    private long pipelineLayout;
    private long pipeline;
    private long descriptorSetLayout;
    private long descriptorPool;
    private long descriptorSet;
    private List<UBO> buffers = new ArrayList<>();
    private List<ImageDescriptor> imageDescriptors = new ArrayList<>();
    private PushConstants pushConstants;
    private boolean valid;

    public ComputePipeline(String name) {
        this.name = name;
    }

    public ComputePipeline compile(ShaderEntry entry) {
        this.computeSPIRV = entry.getComputeSPIRV();
        if (this.computeSPIRV == null) {
            LOGGER.warn("No compute SPIRV for '{}', trying fallback", name);
            this.computeSPIRV = FallbackShader.getInstance().getOrCreateFallback(ShaderKind.COMPUTE_SHADER);
        }
        return this;
    }

    public ComputePipeline compileFromSource(String source) {
        try {
            this.computeSPIRV = SPIRVUtils.compileShader(name, source, ShaderKind.COMPUTE_SHADER);
        } catch (Exception e) {
            LOGGER.error("Failed to compile compute shader '{}': {}", name, e.getMessage());
            this.computeSPIRV = FallbackShader.getInstance().getOrCreateFallback(ShaderKind.COMPUTE_SHADER);
        }
        return this;
    }

    public void create() {
        destroy();

        try (MemoryStack stack = stackPush()) {
            createDescriptorSetLayout(stack);
            createPipelineLayout(stack);
            createShaderModule();
            createComputePipeline(stack);
            this.valid = true;
            LOGGER.debug("Compute pipeline '{}' created", name);
        } catch (Exception e) {
            LOGGER.error("Failed to create compute pipeline '{}': {}", name, e.getMessage());
            this.valid = false;
        }
    }

    public void dispatch(VkCommandBuffer commandBuffer, int groupX, int groupY, int groupZ) {
        if (!valid) return;
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        vkCmdDispatch(commandBuffer, groupX, groupY, groupZ);
    }

    public void dispatchWithPushConstants(VkCommandBuffer commandBuffer, int groupX, int groupY, int groupZ) {
        if (!valid || pushConstants == null) return;
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        try (MemoryStack stack = stackPush()) {
            ByteBuffer data = stack.malloc(pushConstants.getSize());
            long ptr = org.lwjgl.system.MemoryUtil.memAddress0(data);
            pushConstants.update(ptr);
            VK10.nvkCmdPushConstants(commandBuffer, pipelineLayout,
                VK_SHADER_STAGE_COMPUTE_BIT, 0, pushConstants.getSize(), ptr);
        }
        vkCmdDispatch(commandBuffer, groupX, groupY, groupZ);
    }

    public void allocateDescriptorSet() {
        if (descriptorSetLayout == VK_NULL_HANDLE) return;
        this.descriptorPool = DescriptorPoolManager.getInstance().acquirePool(descriptorSetLayout);
        this.descriptorSet = DescriptorPoolManager.getInstance().allocateDescriptorSet(descriptorPool, descriptorSetLayout);
    }

    public void bindImageDescriptor(int binding, VulkanImage image, int descriptorType) {
        if (descriptorSet == VK_NULL_HANDLE) return;
        try (MemoryStack stack = stackPush()) {
            int layout = descriptorType == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                ? VK_IMAGE_LAYOUT_GENERAL
                : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
            imageInfo.imageLayout(layout);
            imageInfo.imageView(image.getImageView());
            if (descriptorType == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER) {
                imageInfo.sampler(image.getSampler());
            }

            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(descriptorSet)
                .dstBinding(binding)
                .descriptorCount(1)
                .descriptorType(descriptorType)
                .pImageInfo(imageInfo);

            vkUpdateDescriptorSets(DEVICE, write, null);
        }
    }

    public void bindUBO(int binding, ByteBuffer data, int size) {
        if (descriptorSet == VK_NULL_HANDLE) return;
        try (MemoryStack stack = stackPush()) {
            Buffer buffer = new Buffer(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, MemoryTypes.HOST_MEM);
            buffer.createBuffer(size);
            buffer.copyBuffer(data, size);

            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
            bufferInfo.buffer(buffer.getId());
            bufferInfo.offset(0);
            bufferInfo.range(size);

            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(descriptorSet)
                .dstBinding(binding)
                .descriptorCount(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .pBufferInfo(bufferInfo);

            vkUpdateDescriptorSets(DEVICE, write, null);
        }
    }

    public void dispatchWithDescriptors(VkCommandBuffer commandBuffer, int groupX, int groupY, int groupZ) {
        if (!valid) return;
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        if (descriptorSet != VK_NULL_HANDLE) {
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                pipelineLayout, 0, stackPush().longs(descriptorSet), null);
        }
        if (pushConstants != null) {
            try (MemoryStack stack = stackPush()) {
                ByteBuffer data = stack.malloc(pushConstants.getSize());
                long ptr = MemoryUtil.memAddress0(data);
                pushConstants.update(ptr);
                VK10.nvkCmdPushConstants(commandBuffer, pipelineLayout,
                    VK_SHADER_STAGE_COMPUTE_BIT, 0, pushConstants.getSize(), ptr);
            }
        }
        vkCmdDispatch(commandBuffer, groupX, groupY, groupZ);
    }

    public void destroy() {
        if (descriptorSet != VK_NULL_HANDLE && descriptorPool != VK_NULL_HANDLE) {
            DescriptorPoolManager.getInstance().releasePool(descriptorPool);
            descriptorSet = VK_NULL_HANDLE;
            descriptorPool = VK_NULL_HANDLE;
        }
        if (pipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(DEVICE, pipeline, null);
            pipeline = VK_NULL_HANDLE;
        }
        if (pipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(DEVICE, pipelineLayout, null);
            pipelineLayout = VK_NULL_HANDLE;
        }
        if (descriptorSetLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(DEVICE, descriptorSetLayout, null);
            descriptorSetLayout = VK_NULL_HANDLE;
        }
        if (shaderModule != VK_NULL_HANDLE) {
            vkDestroyShaderModule(DEVICE, shaderModule, null);
            shaderModule = VK_NULL_HANDLE;
        }
        this.valid = false;
    }

    public boolean isValid() { return valid; }
    public long getPipeline() { return pipeline; }
    public long getLayout() { return pipelineLayout; }

    public void setBuffers(List<UBO> buffers) { this.buffers = buffers; }
    public void setImageDescriptors(List<ImageDescriptor> descriptors) { this.imageDescriptors = descriptors; }
    public void setPushConstants(PushConstants pushConstants) { this.pushConstants = pushConstants; }
    public void addUBO(UBO ubo) { this.buffers.add(ubo); }
    public void addImageDescriptor(ImageDescriptor desc) { this.imageDescriptors.add(desc); }

    private void createShaderModule() {
        try (MemoryStack stack = stackPush()) {
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
            createInfo.pCode(computeSPIRV.bytecode());
            LongBuffer pModule = stack.mallocLong(1);
            if (vkCreateShaderModule(DEVICE, createInfo, null, pModule) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create compute shader module");
            }
            this.shaderModule = pModule.get(0);
        }
    }

    private void createDescriptorSetLayout(MemoryStack stack) {
        int bindingCount = buffers.size() + imageDescriptors.size();
        if (bindingCount == 0) return;

        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(bindingCount, stack);
        int idx = 0;
        for (UBO ubo : buffers) {
            bindings.get(idx)
                .binding(ubo.getBinding())
                .descriptorCount(1)
                .descriptorType(ubo.getType())
                .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            idx++;
        }
        for (ImageDescriptor desc : imageDescriptors) {
            bindings.get(idx)
                .binding(desc.getBinding())
                .descriptorCount(1)
                .descriptorType(desc.getType())
                .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            idx++;
        }

        VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack);
        layoutInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
        layoutInfo.pBindings(bindings);

        LongBuffer pLayout = stack.mallocLong(1);
        if (vkCreateDescriptorSetLayout(DEVICE, layoutInfo, null, pLayout) != VK_SUCCESS) {
            throw new RuntimeException("Failed to create compute descriptor set layout");
        }
        this.descriptorSetLayout = pLayout.get(0);
    }

    private void createPipelineLayout(MemoryStack stack) {
        VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack);
        layoutInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);

        if (descriptorSetLayout != VK_NULL_HANDLE) {
            layoutInfo.pSetLayouts(stack.longs(descriptorSetLayout));
        }

        if (pushConstants != null) {
            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
            pushRange.stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            pushRange.offset(0);
            pushRange.size(pushConstants.getSize());
            layoutInfo.pPushConstantRanges(pushRange);
        }

        LongBuffer pLayout = stack.mallocLong(1);
        if (vkCreatePipelineLayout(DEVICE, layoutInfo, null, pLayout) != VK_SUCCESS) {
            throw new RuntimeException("Failed to create compute pipeline layout");
        }
        this.pipelineLayout = pLayout.get(0);
    }

    private void createComputePipeline(MemoryStack stack) {
        VkPipelineShaderStageCreateInfo stageInfo = VkPipelineShaderStageCreateInfo.calloc(stack);
        stageInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
        stageInfo.stage(VK_SHADER_STAGE_COMPUTE_BIT);
        stageInfo.module(shaderModule);
        stageInfo.pName(stack.UTF8("main"));

        VkComputePipelineCreateInfo.Buffer createInfoBuffer = VkComputePipelineCreateInfo.calloc(1, stack);
        createInfoBuffer.get(0)
            .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
            .stage(stageInfo)
            .layout(pipelineLayout);

        LongBuffer pPipeline = stack.mallocLong(1);
        int result = vkCreateComputePipelines(DEVICE, VK_NULL_HANDLE, createInfoBuffer, null, pPipeline);
        if (result != VK_SUCCESS) {
            throw new RuntimeException("Failed to create compute pipeline");
        }
        this.pipeline = pPipeline.get(0);
    }
}
