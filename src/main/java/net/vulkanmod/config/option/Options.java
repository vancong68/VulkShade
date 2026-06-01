package net.vulkanmod.config.option;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ParticleStatus;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.gui.OptionBlock;
import net.vulkanmod.config.video.VideoModeManager;
import net.vulkanmod.config.video.VideoModeSet;
import net.vulkanmod.config.video.WindowMode;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.build.light.LightMode;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkshade.config.QualityPreset;
import net.vulkanmod.vulkshade.config.ShaderQuality;
import net.vulkanmod.vulkshade.config.VulkShadeConfig;
import net.vulkanmod.vulkshade.render.VoxyLODManager;

import java.util.stream.IntStream;

public abstract class Options {
    public static boolean fullscreenDirty = false;
    static Config config = Initializer.CONFIG;
    static Minecraft minecraft = Minecraft.getInstance();
    static Window window = minecraft.getWindow();
    static net.minecraft.client.Options minecraftOptions = minecraft.options;

    public static OptionBlock[] getVideoOpts() {
        var videoMode = config.videoMode;
        var videoModeSet = VideoModeManager.getFromVideoMode(videoMode);

        if (videoModeSet == null) {
            videoModeSet = VideoModeSet.getDummy();
            videoMode = videoModeSet.getVideoMode(-1);
        }

        VideoModeManager.selectedVideoMode = videoMode;
        var refreshRates = videoModeSet.getRefreshRates();

        CyclingOption<Integer> RefreshRate = (CyclingOption<Integer>) new CyclingOption<>(
                Component.translatable("vulkanmod.options.refreshRate"),
                refreshRates.toArray(new Integer[0]),
                (value) -> {
                    VideoModeManager.selectedVideoMode.refreshRate = value;
                    VideoModeManager.applySelectedVideoMode();

                    if (minecraftOptions.fullscreen().get())
                        fullscreenDirty = true;
                },
                () -> VideoModeManager.selectedVideoMode.refreshRate)
                .setTranslator(refreshRate -> Component.nullToEmpty(refreshRate.toString()));

        Option<VideoModeSet> resolutionOption = new CyclingOption<>(
                Component.translatable("options.fullscreen.resolution"),
                VideoModeManager.getVideoResolutions(),
                (value) -> {
                    VideoModeManager.selectedVideoMode = value.getVideoMode(RefreshRate.getNewValue());
                    VideoModeManager.applySelectedVideoMode();

                    if (minecraftOptions.fullscreen().get())
                        fullscreenDirty = true;
                },
                () -> {
                    var selectedVideoMode = VideoModeManager.selectedVideoMode;
                    var selectedVideoModeSet = VideoModeManager.getFromVideoMode(selectedVideoMode);

                    return selectedVideoModeSet != null ? selectedVideoModeSet : VideoModeSet.getDummy();
                })
                .setTranslator(resolution -> Component.nullToEmpty(resolution.toString()));

        resolutionOption.setOnChange(() -> {
            var newVideoMode = resolutionOption.getNewValue();
            var newRefreshRates = newVideoMode.getRefreshRates().toArray(new Integer[0]);

            RefreshRate.setValues(newRefreshRates);
            RefreshRate.setNewValue(newRefreshRates[newRefreshRates.length - 1]);
        });

        return new OptionBlock[]{
                new OptionBlock("", new Option<?>[]{
                        resolutionOption,
                        RefreshRate,
                        new CyclingOption<>(Component.translatable("vulkanmod.options.windowMode"),
                                WindowMode.values(),
                                value -> {
                                    boolean exclusiveFullscreen = value == WindowMode.EXCLUSIVE_FULLSCREEN;
                                    minecraftOptions.fullscreen()
                                                    .set(exclusiveFullscreen);

                                    config.windowMode = value.mode;
                                    fullscreenDirty = true;
                                },
                                () -> WindowMode.fromValue(config.windowMode))
                                .setTranslator(value -> Component.translatable(WindowMode.getComponentName(value))),
                        new RangeOption(Component.translatable("options.framerateLimit"),
                                        10, 260, 10,
                                        value -> Component.nullToEmpty(value == 260 ?
                                                                               Component.translatable(
                                                                                                "options.framerateLimit.max")
                                                                                        .getString() :
                                                                               String.valueOf(value)),
                                        value -> {
                                            minecraftOptions.framerateLimit().set(value);
                                            minecraft.getFramerateLimitTracker().setFramerateLimit(value);
                                        },
                                        () -> minecraftOptions.framerateLimit().get()),
                        new SwitchOption(Component.translatable("options.vsync"),
                                         value -> {
                                             minecraftOptions.enableVsync().set(value);
                                             window.updateVsync(value);
                                         },
                                         () -> minecraftOptions.enableVsync().get()),
                        new CyclingOption<>(Component.translatable("options.inactivityFpsLimit"),
                                            InactivityFpsLimit.values(),
                                            value -> minecraftOptions.inactivityFpsLimit().set(value),
                                            () -> minecraftOptions.inactivityFpsLimit().get())
                                .setTranslator(inactivityFpsLimit -> Component.translatable(inactivityFpsLimit.getKey()))
                }),
                new OptionBlock("", new Option<?>[]{
                        new RangeOption(Component.translatable("options.guiScale"),
                                        0, window.calculateScale(0, minecraft.isEnforceUnicode()), 1,
                                        value -> Component.translatable((value == 0)
                                                                                ? "options.guiScale.auto"
                                                                                : String.valueOf(value)),
                                        value -> {
                                            minecraftOptions.guiScale().set(value);
                                            minecraft.resizeDisplay();
                                        },
                                        () -> (minecraftOptions.guiScale().get())),
                        new RangeOption(Component.translatable("options.gamma"),
                                        0, 100, 1,
                                        value -> Component.translatable(switch (value) {
                                            case 0 -> "options.gamma.min";
                                            case 50 -> "options.gamma.default";
                                            case 100 -> "options.gamma.max";
                                            default -> String.valueOf(value);
                                        }),
                                        value -> minecraftOptions.gamma().set(value * 0.01),
                                        () -> (int) (minecraftOptions.gamma().get() * 100.0)),
                }),
                new OptionBlock("", new Option<?>[]{
                        new SwitchOption(Component.translatable("options.viewBobbing"),
                                         (value) -> minecraftOptions.bobView().set(value),
                                         () -> minecraftOptions.bobView().get()),
                        new CyclingOption<>(Component.translatable("options.attackIndicator"),
                                            AttackIndicatorStatus.values(),
                                            value -> minecraftOptions.attackIndicator().set(value),
                                            () -> minecraftOptions.attackIndicator().get())
                                .setTranslator(value -> Component.translatable(value.getKey())),
                        new SwitchOption(Component.translatable("options.autosaveIndicator"),
                                         value -> minecraftOptions.showAutosaveIndicator().set(value),
                                         () -> minecraftOptions.showAutosaveIndicator().get()),
                })
        };
    }

    public static OptionBlock[] getGraphicsOpts() {
        return new OptionBlock[]{
                new OptionBlock("", new Option<?>[]{
                        new RangeOption(Component.translatable("options.renderDistance"),
                                        2, 32, 1,
                                        (value) -> minecraftOptions.renderDistance().set(value),
                                        () -> minecraftOptions.renderDistance().get()),
                        new RangeOption(Component.translatable("options.simulationDistance"),
                                        5, 32, 1,
                                        (value) -> minecraftOptions.simulationDistance().set(value),
                                        () -> minecraftOptions.simulationDistance().get()),
                        new CyclingOption<>(Component.translatable("options.prioritizeChunkUpdates"),
                                            PrioritizeChunkUpdates.values(),
                                            value -> minecraftOptions.prioritizeChunkUpdates().set(value),
                                            () -> minecraftOptions.prioritizeChunkUpdates().get())
                                .setTranslator(value -> Component.translatable(value.getKey())),
                        new CyclingOption<>(Component.translatable("vulkanmod.options.qualityPreset"),
                                            QualityPreset.values(),
                                            value -> {
                                                value.apply();
                                                VulkShadeConfig.getInstance().applyPreset(value);
                                            },
                                            () -> QualityPreset.detectCurrent())
                                .setTranslator(preset -> Component.nullToEmpty(preset.getDisplayName()))
                                .setTooltip(Component.translatable("vulkanmod.options.qualityPreset.tooltip")),
                }),
                new OptionBlock("", new Option<?>[]{
                        new CyclingOption<>(Component.translatable("options.graphics"),
                                            new GraphicsStatus[]{GraphicsStatus.FAST, GraphicsStatus.FANCY},
                                            value -> minecraftOptions.graphicsMode().set(value),
                                            () -> minecraftOptions.graphicsMode().get())
                                .setTranslator(graphicsMode -> Component.translatable(graphicsMode.getKey())),
                        new CyclingOption<>(Component.translatable("options.particles"),
                                            new ParticleStatus[]{ParticleStatus.MINIMAL, ParticleStatus.DECREASED, ParticleStatus.ALL},
                                            value -> minecraftOptions.particles().set(value),
                                            () -> minecraftOptions.particles().get())
                                .setTranslator(particlesMode -> Component.translatable(particlesMode.getKey())),
                        new CyclingOption<>(Component.translatable("options.renderClouds"),
                                            CloudStatus.values(),
                                            value -> minecraftOptions.cloudStatus().set(value),
                                            () -> minecraftOptions.cloudStatus().get())
                                .setTranslator(value -> Component.translatable(value.getKey())),
                        new RangeOption(Component.translatable("options.renderCloudsDistance"),
                                        2, 128, 1,
                                        (value) -> minecraftOptions.cloudRange().set(value),
                                        () -> minecraftOptions.cloudRange().get()),
                        new CyclingOption<>(Component.translatable("options.ao"),
                                            new Integer[]{LightMode.FLAT, LightMode.SMOOTH, LightMode.SUB_BLOCK},
                                            (value) -> {
                                                if (value > LightMode.FLAT)
                                                    minecraftOptions.ambientOcclusion().set(true);
                                                else
                                                    minecraftOptions.ambientOcclusion().set(false);

                                                config.ambientOcclusion = value;

                                                minecraft.levelRenderer.allChanged();
                                            },
                                            () -> config.ambientOcclusion)
                                .setTranslator(value -> Component.translatable(switch (value) {
                                    case LightMode.FLAT -> "options.off";
                                    case LightMode.SMOOTH -> "options.on";
                                    case LightMode.SUB_BLOCK -> "vulkanmod.options.ao.subBlock";
                                    default -> "vulkanmod.options.unknown";
                                }))
                                .setTooltip(Component.translatable("vulkanmod.options.ao.subBlock.tooltip")),
                        new RangeOption(Component.translatable("options.biomeBlendRadius"),
                                        0, 7, 1,
                                        value -> {
                                            int v = value * 2 + 1;
                                            return Component.nullToEmpty("%d x %d".formatted(v, v));
                                        },
                                        (value) -> {
                                            minecraftOptions.biomeBlendRadius().set(value);
                                            minecraft.levelRenderer.allChanged();
                                        },
                                        () -> minecraftOptions.biomeBlendRadius().get()),
                }),
                new OptionBlock("", new Option<?>[]{
                        new SwitchOption(Component.translatable("options.entityShadows"),
                                         value -> minecraftOptions.entityShadows().set(value),
                                         () -> minecraftOptions.entityShadows().get()),
                        new RangeOption(Component.translatable("options.entityDistanceScaling"),
                                        50, 500, 25,
                                        value -> minecraftOptions.entityDistanceScaling().set(value * 0.01),
                                        () -> minecraftOptions.entityDistanceScaling().get().intValue() * 100),
                        new CyclingOption<>(Component.translatable("options.mipmapLevels"),
                                            new Integer[]{0, 1, 2, 3, 4},
                                            value -> {
                                                minecraftOptions.mipmapLevels().set(value);
                                                minecraft.updateMaxMipLevel(value);
                                                minecraft.delayTextureReload();
                                            },
                                            () -> minecraftOptions.mipmapLevels().get())
                                .setTranslator(value -> Component.nullToEmpty(value.toString()))
                })
        };
    }

    public static OptionBlock[] getOptimizationOpts() {
        return new OptionBlock[]{
                new OptionBlock("", new Option[]{
                        new CyclingOption<>(Component.translatable("vulkanmod.options.advCulling"),
                                            new Integer[]{1, 2, 3, 10},
                                            value -> config.advCulling = value,
                                            () -> config.advCulling)
                                .setTranslator(value -> Component.translatable(switch (value) {
                                    case 1 -> "vulkanmod.options.advCulling.aggressive";
                                    case 2 -> "vulkanmod.options.advCulling.normal";
                                    case 3 -> "vulkanmod.options.advCulling.conservative";
                                    case 10 -> "options.off";
                                    default -> "vulkanmod.options.unknown";
                                }))
                                .setTooltip(Component.translatable("vulkanmod.options.advCulling.tooltip")),
                        new SwitchOption(Component.translatable("vulkanmod.options.entityCulling"),
                                         value -> config.entityCulling = value,
                                         () -> config.entityCulling)
                                .setTooltip(Component.translatable("vulkanmod.options.entityCulling.tooltip")),
                        new SwitchOption(Component.translatable("vulkanmod.options.uniqueOpaqueLayer"),
                                         value -> {
                                             config.uniqueOpaqueLayer = value;
                                             TerrainRenderType.updateMapping();
                                             minecraft.levelRenderer.allChanged();
                                         },
                                         () -> config.uniqueOpaqueLayer)
                                .setTooltip(Component.translatable("vulkanmod.options.uniqueOpaqueLayer.tooltip")),
                        new SwitchOption(Component.translatable("vulkanmod.options.backfaceCulling"),
                                         value -> {
                                             config.backFaceCulling = value;
                                             Minecraft.getInstance().levelRenderer.allChanged();
                                         },
                                         () -> config.backFaceCulling)
                                .setTooltip(Component.translatable("vulkanmod.options.backfaceCulling.tooltip")),
                        new SwitchOption(Component.translatable("vulkanmod.options.indirectDraw"),
                                         value -> config.indirectDraw = value,
                                         () -> config.indirectDraw)
                                .setTooltip(Component.translatable("vulkanmod.options.indirectDraw.tooltip")),
                }),
                new OptionBlock("", new Option[]{
                        new SwitchOption(Component.translatable("vulkanmod.options.fsr"),
                                         value -> {
                                             config.fsrEnabled = value;
                                             Renderer.scheduleSwapChainUpdate();
                                         },
                                         () -> config.fsrEnabled)
                                .setTooltip(Component.translatable("vulkanmod.options.fsr.tooltip")),
                        new RangeOption(Component.translatable("vulkanmod.options.fsrInternalScale"),
                                        50, 100, 1,
                                        Options::formatFsrScaleValue,
                                        value -> {
                                            config.fsrInternalScale = value;
                                            Renderer.scheduleSwapChainUpdate();
                                        },
                                        () -> config.fsrInternalScale)
                                .setTooltip(Component.translatable("vulkanmod.options.fsrInternalScale.tooltip")),
                        new RangeOption(Component.translatable("vulkanmod.options.casSharpness"),
                                        0, 100, 1,
                                        value -> value == 0
                                                ? Component.translatable("options.off")
                                                : Component.nullToEmpty(value + "%"),
                                        value -> config.fsrSharpness = value,
                                        () -> config.fsrSharpness)
                                .setTooltip(Component.translatable("vulkanmod.options.casSharpness.tooltip")),
                })
        };

    }

    public static OptionBlock[] getOtherOpts() {
        return new OptionBlock[]{
                new OptionBlock("", new Option[]{
                        new RangeOption(Component.translatable("vulkanmod.options.builderThreads"),
                                        0, (Runtime.getRuntime().availableProcessors() - 1), 1,
                                        value -> {
                                            config.builderThreads = value;
                                            WorldRenderer.getInstance().getTaskDispatcher().createThreads(value);
                                        },
                                        () -> config.builderThreads)
                                .setTranslator(value -> {
                            if (value == 0)
                                return Component.translatable("vulkanmod.options.builderThreads.auto");
                            else
                                return Component.nullToEmpty(String.valueOf(value));
                        }),
                        new RangeOption(Component.translatable("vulkanmod.options.frameQueue"),
                                        2, 5, 1,
                                        value -> {
                                            config.frameQueueSize = value;
                                            Renderer.scheduleSwapChainUpdate();
                                        }, () -> config.frameQueueSize)
                                .setTooltip(Component.translatable("vulkanmod.options.frameQueue.tooltip")),
                        new SwitchOption(Component.translatable("vulkanmod.options.textureAnimations"),
                                         value -> {
                                             config.textureAnimations = value;
                                         },
                                         () -> config.textureAnimations),
                }),
                new OptionBlock("", new Option[]{
                        new CyclingOption<>(Component.translatable("vulkanmod.options.deviceSelector"),
                                            IntStream.range(-1, DeviceManager.suitableDevices.size()).boxed()
                                                     .toArray(Integer[]::new),
                                            value -> config.device = value,
                                            () -> config.device)
                                .setTranslator(value -> Component.translatable((value == -1)
                                                                                       ? "vulkanmod.options.deviceSelector.auto"
                                                                                       : DeviceManager.suitableDevices.get(
                                        value).deviceName)
                                )
                                .setTooltip(Component.nullToEmpty("%s: %s".formatted(
                                Component.translatable("vulkanmod.options.deviceSelector.tooltip").getString(),
                                DeviceManager.device.deviceName)))
                })
        };

    }

    public static OptionBlock[] getShaderOpts() {
        VulkShadeConfig vcfg = VulkShadeConfig.getInstance();
        return new OptionBlock[]{
                new OptionBlock("", new Option[]{
                        new CyclingOption<>(Component.translatable("vulkanmod.options.shaderQuality"),
                                            ShaderQuality.values(),
                                            value -> {
                                                value.apply();
                                            },
                                            () -> ShaderQuality.detectCurrent())
                                .setTranslator(q -> Component.nullToEmpty(q.getDisplayName()))
                                .setTooltip(Component.translatable("vulkanmod.options.shaderQuality.tooltip")),
                        new CyclingOption<>(Component.translatable("vulkanmod.options.terrainLightingQuality"),
                                            new Integer[]{0, 1, 2},
                                            value -> config.terrainLightingQuality = value,
                                            () -> config.terrainLightingQuality)
                                .setTranslator(value -> Component.translatable(switch (value) {
                                    case 0 -> "vulkanmod.options.terrainLightingQuality.full";
                                    case 1 -> "vulkanmod.options.terrainLightingQuality.fast";
                                    case 2 -> "vulkanmod.options.terrainLightingQuality.vanilla";
                                    default -> "vulkanmod.options.unknown";
                                }))
                                .setTooltip(Component.translatable("vulkanmod.options.terrainLightingQuality.tooltip")),
                        new CyclingOption<>(Component.translatable("vulkanmod.options.terrainShadowQuality"),
                                            new Integer[]{0, 1, 2},
                                            value -> config.terrainShadowQuality = value,
                                            () -> config.terrainShadowQuality)
                                .setTranslator(value -> Component.translatable(switch (value) {
                                    case 0 -> "vulkanmod.options.terrainShadowQuality.high";
                                    case 1 -> "vulkanmod.options.terrainShadowQuality.fast";
                                    case 2 -> "options.off";
                                    default -> "vulkanmod.options.unknown";
                                }))
                                .setTooltip(Component.translatable("vulkanmod.options.terrainShadowQuality.tooltip")),
                        new CyclingOption<>(Component.translatable("vulkanmod.options.skyQuality"),
                                            new Integer[]{0, 1, 2, 3},
                                            value -> config.skyQuality = value,
                                            () -> config.skyQuality)
                                .setTranslator(value -> Component.translatable(switch (value) {
                                    case 0 -> "vulkanmod.options.skyQuality.vanilla";
                                    case 1 -> "vulkanmod.options.skyQuality.low";
                                    case 2 -> "vulkanmod.options.skyQuality.medium";
                                    case 3 -> "vulkanmod.options.skyQuality.high";
                                    default -> "vulkanmod.options.unknown";
                                }))
                                .setTooltip(Component.translatable("vulkanmod.options.skyQuality.tooltip")),
                        new CyclingOption<>(Component.translatable("vulkanmod.options.volumetricResolution"),
                                            new Integer[]{1, 2, 4},
                                            value -> config.volumetricResolutionScale = value,
                                            () -> config.volumetricResolutionScale)
                                .setTranslator(value -> Component.translatable(switch (value) {
                                    case 1 -> "vulkanmod.options.volumetricResolution.full";
                                    case 2 -> "vulkanmod.options.volumetricResolution.half";
                                    case 4 -> "vulkanmod.options.volumetricResolution.quarter";
                                    default -> "vulkanmod.options.unknown";
                                }))
                                .setTooltip(Component.translatable("vulkanmod.options.volumetricResolution.tooltip")),
                        new CyclingOption<>(Component.translatable("vulkanmod.options.waterQuality"),
                                            new Integer[]{0, 1, 2, 3},
                                            value -> config.waterQuality = value,
                                            () -> config.waterQuality)
                                .setTranslator(value -> Component.translatable(switch (value) {
                                    case 0 -> "vulkanmod.options.waterQuality.full";
                                    case 1 -> "vulkanmod.options.waterQuality.fast";
                                    case 2 -> "vulkanmod.options.waterQuality.simple";
                                    case 3 -> "vulkanmod.options.waterQuality.vanilla";
                                    default -> "vulkanmod.options.unknown";
                                }))
                                .setTooltip(Component.translatable("vulkanmod.options.waterQuality.tooltip")),
                        new SwitchOption(Component.translatable("vulkanmod.options.volumetricLighting"),
                                         value -> config.volumetricLighting = value,
                                         () -> config.volumetricLighting)
                                .setTooltip(Component.translatable("vulkanmod.options.volumetricLighting.tooltip")),
                        new SwitchOption(Component.translatable("vulkanmod.options.blockEmissiveTextures"),
                                         value -> config.blockEmissiveTextures = value,
                                         () -> config.blockEmissiveTextures)
                                .setTooltip(Component.translatable("vulkanmod.options.blockEmissiveTextures.tooltip"))
                }),
                new OptionBlock("Shader Features", new Option[]{
                        new SwitchOption(Component.translatable("vulkshade.effects.ssao"),
                                         value -> vcfg.setSSAOEnabled(value),
                                         () -> config.featureSSAO)
                                .setTooltip(Component.translatable("vulkanmod.options.feature.ssao.tooltip")),
                        new SwitchOption(Component.translatable("vulkshade.effects.bloom"),
                                         value -> vcfg.setBloomEnabled(value),
                                         () -> config.featureBloom)
                                .setTooltip(Component.translatable("vulkanmod.options.feature.bloom.tooltip")),
                        new RangeOption(Component.translatable("vulkshade.effects.bloomStrength"),
                                        0, 100, 5,
                                        value -> Component.nullToEmpty(value + "%"),
                                        value -> vcfg.setBloomIntensity(value / 100.0f),
                                        () -> (int)(vcfg.getBloomIntensity() * 100))
                                .setTooltip(Component.translatable("vulkshade.effects.bloomStrength.tooltip")),
                        new SwitchOption(Component.translatable("vulkshade.effects.pbr"),
                                         value -> vcfg.setPBRenabled(value),
                                         () -> config.featurePBR)
                                .setTooltip(Component.translatable("vulkanmod.options.feature.pbr.tooltip")),
                        new SwitchOption(Component.translatable("vulkanmod.options.feature.fog"),
                                         value -> vcfg.setFogEnabled(value),
                                         () -> config.featureFog)
                                .setTooltip(Component.translatable("vulkanmod.options.feature.fog.tooltip")),
                        new SwitchOption(Component.translatable("vulkanmod.options.feature.motionBlur"),
                                         value -> vcfg.setMotionBlurEnabled(value),
                                         () -> config.featureMotionBlur)
                                .setTooltip(Component.translatable("vulkanmod.options.feature.motionBlur.tooltip")),
                        new RangeOption(Component.translatable("vulkshade.effects.motionBlurStrength"),
                                        0, 95, 5,
                                        value -> Component.nullToEmpty(value + "%"),
                                        value -> vcfg.setMotionBlurStrength(value / 100.0f),
                                        () -> (int)(vcfg.getMotionBlurStrength() * 100))
                                .setTooltip(Component.translatable("vulkshade.effects.motionBlurStrength.tooltip")),
                        new SwitchOption(Component.translatable("vulkanmod.options.feature.lensFlare"),
                                         value -> vcfg.setLensFlareEnabled(value),
                                         () -> config.featureLensFlare)
                                .setTooltip(Component.translatable("vulkanmod.options.feature.lensFlare.tooltip")),
                })
        };
    }

    public static OptionBlock[] getPerformanceOpts() {
        VulkShadeConfig vcfg = VulkShadeConfig.getInstance();
        return new OptionBlock[]{
                new OptionBlock("", new Option<?>[]{
                        new SwitchOption(Component.translatable("vulkanmod.options.adaptivePerformance"),
                                         value -> vcfg.setAdaptivePerformance(value),
                                         () -> config.adaptivePerformance)
                                .setTooltip(Component.translatable("vulkanmod.options.adaptivePerformance.tooltip")),
                        new SwitchOption(Component.translatable("vulkanmod.options.framePacing"),
                                         value -> vcfg.setFramePacingEnabled(value),
                                         () -> config.framePacing)
                                .setTooltip(Component.translatable("vulkanmod.options.framePacing.tooltip")),
                        new RangeOption(Component.translatable("vulkanmod.options.framePacingTarget"),
                                        30, 240, 10,
                                        value -> Component.nullToEmpty(value >= 240 ? "Unlimited" : value + " FPS"),
                                        value -> vcfg.setFramePacingTarget(value),
                                        () -> config.framePacingTarget)
                                .setTooltip(Component.translatable("vulkanmod.options.framePacingTarget.tooltip")),
                }),
                new OptionBlock("", new Option<?>[]{
                        new SwitchOption(Component.translatable("vulkanmod.options.chunkBatchRendering"),
                                         value -> vcfg.setChunkBatchRendering(value),
                                         () -> config.chunkBatchRendering)
                                .setTooltip(Component.translatable("vulkanmod.options.chunkBatchRendering.tooltip")),
                        new SwitchOption(Component.translatable("vulkanmod.options.dynamicRenderDistance"),
                                         value -> vcfg.setDynamicRenderDistance(value),
                                         () -> config.dynamicRenderDistance)
                                .setTooltip(Component.translatable("vulkanmod.options.dynamicRenderDistance.tooltip")),
                }),
                new OptionBlock("", new Option<?>[]{
                        new SwitchOption(Component.translatable("vulkanmod.options.voxyLOD"),
                                         value -> vcfg.setVoxyLODEnabled(value),
                                         () -> config.voxyLODEnabled)
                                .setTooltip(Component.translatable("vulkanmod.options.voxyLOD.tooltip")),
                        new CyclingOption<>(Component.translatable("vulkanmod.options.voxyLODQuality"),
                                            new VoxyLODManager.LODQuality[]{
                                                VoxyLODManager.LODQuality.LOW,
                                                VoxyLODManager.LODQuality.MEDIUM,
                                                VoxyLODManager.LODQuality.HIGH
                                            },
                                            value -> vcfg.setLODQuality(value),
                                            () -> vcfg.getLODQuality())
                                .setTranslator(q -> Component.nullToEmpty(q.name()))
                                .setTooltip(Component.translatable("vulkanmod.options.voxyLODQuality.tooltip")),
                        new CyclingOption<>(Component.translatable("vulkanmod.options.voxyLODMaxDistance"),
                                            new Integer[]{256, 512, 1024},
                                            value -> vcfg.setMaxLODViewDistance(value),
                                            () -> config.voxyLODMaxDistance)
                                .setTranslator(d -> Component.nullToEmpty(d + " blocks"))
                                .setTooltip(Component.translatable("vulkanmod.options.voxyLODMaxDistance.tooltip")),
                })
        };
    }

    private static Component formatFsrScaleValue(int scale) {
        int outputWidth = Math.max(1, window.getWidth());
        int outputHeight = Math.max(1, window.getHeight());
        int internalWidth = Math.max(1, Math.round(outputWidth * (scale / 100.0f)));
        int internalHeight = Math.max(1, Math.round(outputHeight * (scale / 100.0f)));
        return Component.nullToEmpty("%d%% (%dx%d -> %dx%d)".formatted(scale, internalWidth, internalHeight, outputWidth, outputHeight));
    }
}
