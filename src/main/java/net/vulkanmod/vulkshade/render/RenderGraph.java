package net.vulkanmod.vulkshade.render;

import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

public class RenderGraph {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-RenderGraph");

    private final String name;
    private final List<RenderGraphNode> nodes = new ArrayList<>();
    private final Map<String, RenderGraphNode> nodeMap = new LinkedHashMap<>();
    private final Map<String, TextureResource> texturePool = new HashMap<>();
    private boolean finalized = false;

    public RenderGraph(String name) {
        this.name = name;
    }

    public RenderGraphNode addNode(String name) {
        if (nodeMap.containsKey(name)) {
            throw new IllegalArgumentException("Node '" + name + "' already exists in graph '" + this.name + "'");
        }
        RenderGraphNode node = new RenderGraphNode(name);
        nodes.add(node);
        nodeMap.put(name, node);
        return node;
    }

    public RenderGraphNode getNode(String name) {
        return nodeMap.get(name);
    }

    public void addEdge(String from, String to) {
        RenderGraphNode fromNode = nodeMap.get(from);
        RenderGraphNode toNode = nodeMap.get(to);
        if (fromNode == null || toNode == null) {
            throw new IllegalArgumentException("Cannot add edge: unknown node");
        }
        fromNode.addOutput(toNode);
        toNode.addInput(fromNode);
    }

    public void compile() {
        if (finalized) return;

        List<RenderGraphNode> sorted = topologicalSort();
        if (sorted == null) {
            LOGGER.error("Render graph '{}' contains a cycle!", name);
            return;
        }

        for (RenderGraphNode node : sorted) {
            node.compile();
        }

        this.finalized = true;
        LOGGER.info("Render graph '{}' compiled with {} nodes", name, sorted.size());
    }

    public void execute(VkCommandBuffer cmdBuffer) {
        if (!finalized) {
            compile();
        }

        List<RenderGraphNode> sorted = topologicalSort();
        if (sorted == null) return;

        for (RenderGraphNode node : sorted) {
            if (node.isEnabled()) {
                node.execute(cmdBuffer);
            }
        }
    }

    public void resize(int width, int height) {
        this.finalized = false;
        for (RenderGraphNode node : nodes) {
            node.resize(width, height);
        }
    }

    public void cleanup() {
        for (RenderGraphNode node : nodes) {
            node.cleanup();
        }
        nodes.clear();
        nodeMap.clear();
        texturePool.clear();
        finalized = false;
    }

    public TextureResource createTexture(String name, int width, int height, int format) {
        TextureResource tex = new TextureResource(name, width, height, format);
        texturePool.put(name, tex);
        return tex;
    }

    public TextureResource getTexture(String name) {
        return texturePool.get(name);
    }

    private List<RenderGraphNode> topologicalSort() {
        Map<RenderGraphNode, Integer> inDegree = new HashMap<>();
        for (RenderGraphNode node : nodes) {
            inDegree.putIfAbsent(node, 0);
        }
        for (RenderGraphNode node : nodes) {
            for (RenderGraphNode output : node.outputs) {
                inDegree.merge(output, 1, Integer::sum);
            }
        }

        LinkedList<RenderGraphNode> queue = new LinkedList<>();
        List<RenderGraphNode> result = new ArrayList<>();
        Set<RenderGraphNode> visited = new HashSet<>();

        for (Map.Entry<RenderGraphNode, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        while (!queue.isEmpty()) {
            RenderGraphNode node = queue.poll();
            if (!visited.add(node)) continue;
            result.add(node);
            for (RenderGraphNode output : node.outputs) {
                inDegree.merge(output, -1, Integer::sum);
                if (inDegree.get(output) == 0) {
                    queue.add(output);
                }
            }
        }

        if (result.size() != nodes.size()) {
            return null;
        }
        return result;
    }

    public static class RenderGraphNode {
        private final String name;
        private final List<RenderGraphNode> inputs = new ArrayList<>();
        private final List<RenderGraphNode> outputs = new ArrayList<>();
        private RenderPass renderPass;
        private Framebuffer framebuffer;
        private boolean enabled = true;
        private Runnable executeCallback;

        RenderGraphNode(String name) {
            this.name = name;
        }

        public String getName() { return name; }

        void addInput(RenderGraphNode node) { inputs.add(node); }
        void addOutput(RenderGraphNode node) { outputs.add(node); }

        public void setRenderPass(RenderPass pass) { this.renderPass = pass; }
        public void setFramebuffer(Framebuffer fb) { this.framebuffer = fb; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isEnabled() { return enabled; }

        public void setExecuteCallback(Runnable callback) {
            this.executeCallback = callback;
        }

        void compile() {
        }

        void execute(VkCommandBuffer cmdBuffer) {
            if (executeCallback != null) {
                executeCallback.run();
            }
        }

        void resize(int width, int height) {
            if (framebuffer != null) {
            }
        }

        void cleanup() {
            if (framebuffer != null) {
                framebuffer.cleanUp();
            }
            if (renderPass != null) {
                renderPass.cleanUp();
            }
        }
    }

    public static class TextureResource {
        private final String name;
        private int width;
        private int height;
        private int format;
        private VulkanImage image;

        TextureResource(String name, int width, int height, int format) {
            this.name = name;
            this.width = width;
            this.height = height;
            this.format = format;
        }

        public String getName() { return name; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public int getFormat() { return format; }
        public VulkanImage getImage() { return image; }

        void resize(int w, int h) {
            this.width = w;
            this.height = h;
            if (image != null) {
                image.free();
                image = null;
            }
        }
    }
}
