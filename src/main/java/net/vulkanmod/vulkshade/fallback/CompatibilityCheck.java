package net.vulkanmod.vulkshade.fallback;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memASCII;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

public class CompatibilityCheck {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-Compatibility");

    private static CompatibilityCheck INSTANCE;

    private boolean vulkanAvailable = false;
    private boolean vulkan12Supported = false;
    private boolean swapchainSupported = false;
    private boolean dynamicRenderingSupported = false;
    private boolean shaderDrawParametersSupported = false;
    private String deviceName = "Unknown";
    private String driverVersion = "Unknown";
    private int apiVersion = 0;

    private final List<String> warnings = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    private static final int VK_API_VERSION_1_0 = VK_MAKE_API_VERSION(0, 1, 0, 0);
    private static final int VK_API_VERSION_1_1 = VK_MAKE_API_VERSION(0, 1, 1, 0);
    private static final int VK_API_VERSION_1_3 = VK_MAKE_API_VERSION(0, 1, 3, 0);

    private CompatibilityCheck() {
    }

    public static CompatibilityCheck getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new CompatibilityCheck();
        }
        return INSTANCE;
    }

    public boolean checkVulkanSupport() {
        try {
            vulkanAvailable = GLFWVulkan.glfwVulkanSupported();
            if (!vulkanAvailable) {
                errors.add("Vulkan is not available on this system");
                return false;
            }

            try (MemoryStack stack = stackPush()) {
                int[] version = new int[1];
                int result = vkEnumerateInstanceVersion(version);
                if (result != VK_SUCCESS) {
                    errors.add("Failed to enumerate Vulkan instance version");
                    return false;
                }
                this.apiVersion = version[0];
                int major = VK_VERSION_MAJOR(apiVersion);
                int minor = VK_VERSION_MINOR(apiVersion);

                vulkan12Supported = apiVersion >= VK_API_VERSION_1_2;

                if (!vulkan12Supported) {
                    warnings.add("Vulkan 1.2+ recommended, found " + major + "." + minor);
                }

                PointerBuffer extensions = getAvailableInstanceExtensions(stack);
                boolean khrSurface = false;
                boolean khrWin32Surface = false;

                if (extensions != null) {
                    for (int i = 0; i < extensions.capacity(); i++) {
                        long extPtr = extensions.get(i);
                        String extName = memASCII(extPtr);
                        if (extName == null) continue;
                        switch (extName) {
                            case "VK_KHR_surface" -> khrSurface = true;
                            case "VK_KHR_win32_surface" -> khrWin32Surface = true;
                        }
                    }
                }

                swapchainSupported = khrSurface && khrWin32Surface;
                if (!swapchainSupported) {
                    errors.add("Swapchain extensions not available");
                    return false;
                }

                List<VkPhysicalDevice> devices = enumeratePhysicalDevices();
                if (devices.isEmpty()) {
                    errors.add("No Vulkan-capable GPU found");
                    return false;
                }

                VkPhysicalDevice bestDevice = findBestDevice(devices);
                if (bestDevice == null) {
                    errors.add("No suitable GPU device found");
                    return false;
                }

                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(bestDevice, props);
                this.deviceName = props.deviceNameString();
                int dvMaj = VK_VERSION_MAJOR(props.driverVersion());
                int dvMin = VK_VERSION_MINOR(props.driverVersion());
                int dvPat = VK_VERSION_PATCH(props.driverVersion());
                this.driverVersion = String.format("%d.%d.%d", dvMaj, dvMin, dvPat);

                checkDeviceExtensions(bestDevice, stack);

                LOGGER.info("Vulkan compatibility check passed");
                LOGGER.info("  Device: {}", deviceName);
                LOGGER.info("  Vulkan API: {}.{}", major, minor);
                LOGGER.info("  Driver: {}", driverVersion);

                return true;
            }
        } catch (Exception e) {
            errors.add("Vulkan check crashed: " + e.getMessage());
            LOGGER.error("Vulkan compatibility check threw an exception", e);
            return false;
        }
    }

    public boolean isFullyCompatible() {
        return vulkanAvailable && vulkan12Supported && swapchainSupported && errors.isEmpty();
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public List<String> getWarnings() { return Collections.unmodifiableList(warnings); }
    public List<String> getErrors() { return Collections.unmodifiableList(errors); }

    public String getDeviceName() { return deviceName; }
    public String getDriverVersion() { return driverVersion; }
    public int getApiVersion() { return apiVersion; }
    public boolean isVulkanAvailable() { return vulkanAvailable; }
    public boolean isVulkan12Supported() { return vulkan12Supported; }
    public boolean isSwapchainSupported() { return swapchainSupported; }
    public boolean isDynamicRenderingSupported() { return dynamicRenderingSupported; }
    public boolean isShaderDrawParametersSupported() { return shaderDrawParametersSupported; }

    private PointerBuffer getAvailableInstanceExtensions(MemoryStack stack) {
        int[] count = new int[1];
        int result = vkEnumerateInstanceExtensionProperties((String) null, count, null);
        if (result != VK_SUCCESS) return null;

        VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count[0], stack);
        result = vkEnumerateInstanceExtensionProperties((String) null, count, props);
        if (result != VK_SUCCESS) return null;

        PointerBuffer extensions = stack.mallocPointer(count[0]);
        for (int i = 0; i < count[0]; i++) {
            extensions.put(stack.ASCII(props.get(i).extensionNameString()));
        }
        return extensions.rewind();
    }

    private List<VkPhysicalDevice> enumeratePhysicalDevices() {
        List<VkPhysicalDevice> devices = new ArrayList<>();

        try (MemoryStack stack = stackPush()) {
            VkInstance tempInstance = createTempInstance(stack);
            if (tempInstance == null) return devices;

            int[] count = new int[1];
            int result = vkEnumeratePhysicalDevices(tempInstance, count, null);
            if (result != VK_SUCCESS || count[0] == 0) return devices;

            PointerBuffer pDevices = stack.mallocPointer(count[0]);
            result = vkEnumeratePhysicalDevices(tempInstance, count, pDevices);
            if (result != VK_SUCCESS) return devices;

            for (int i = 0; i < count[0]; i++) {
                devices.add(new VkPhysicalDevice(pDevices.get(i), tempInstance));
            }
        }

        return devices;
    }

    private VkInstance createTempInstance(MemoryStack stack) {
        VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack);
        appInfo.sType(VK_STRUCTURE_TYPE_APPLICATION_INFO);
        appInfo.apiVersion(VK_API_VERSION_1_2);

        VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack);
        createInfo.sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO);
        createInfo.pApplicationInfo(appInfo);

        PointerBuffer pInstance = stack.mallocPointer(1);
        int result = vkCreateInstance(createInfo, null, pInstance);
        if (result != VK_SUCCESS) return null;

        return new VkInstance(pInstance.get(0), createInfo);
    }

    private VkPhysicalDevice findBestDevice(List<VkPhysicalDevice> devices) {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack);

            VkPhysicalDevice best = null;
            int bestScore = -1;

            for (VkPhysicalDevice device : devices) {
                vkGetPhysicalDeviceProperties(device, props);
                vkGetPhysicalDeviceFeatures(device, features);

                int score = 0;
                switch (props.deviceType()) {
                    case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> score = 1000;
                    case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> score = 500;
                    case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> score = 300;
                    default -> score = 100;
                }

                if (features.tessellationShader()) score += 100;
                if (features.geometryShader()) score += 50;
                if (features.samplerAnisotropy()) score += 50;
                if (features.multiDrawIndirect()) score += 100;

                if (score > bestScore) {
                    bestScore = score;
                    best = device;
                }
            }

            return best;
        }
    }

    private void checkDeviceExtensions(VkPhysicalDevice device, MemoryStack stack) {
        int[] count = new int[1];
        int result = vkEnumerateDeviceExtensionProperties(device, (String) null, count, null);
        if (result != VK_SUCCESS) return;

        VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count[0], stack);
        result = vkEnumerateDeviceExtensionProperties(device, (String) null, count, props);
        if (result != VK_SUCCESS) return;

        for (int i = 0; i < count[0]; i++) {
            String name = props.get(i).extensionNameString();
            if ("VK_KHR_dynamic_rendering".equals(name)) dynamicRenderingSupported = true;
            if ("VK_KHR_shader_draw_parameters".equals(name)) shaderDrawParametersSupported = true;
        }
    }
}
