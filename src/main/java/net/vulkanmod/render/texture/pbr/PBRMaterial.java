package net.vulkanmod.render.texture.pbr;

public class PBRMaterial {

    public float roughness;
    public float metallic;
    public float f0;
    public float ao;
    public float emissive;
    public float height;
    public float sss;
    public float porosity;
    public int metalID;

    public static final PBRMaterial DEFAULT = new PBRMaterial();

    public PBRMaterial() {
        this.roughness = 0.8f;
        this.metallic = 0.0f;
        this.f0 = 0.04f;
        this.ao = 1.0f;
        this.emissive = 0.0f;
        this.height = 0.0f;
        this.sss = 0.0f;
        this.porosity = 0.0f;
        this.metalID = 0;
    }

    public PBRMaterial(float roughness, float metallic, float f0, float ao,
                       float emissive, float height, float sss, float porosity, int metalID) {
        this.roughness = roughness;
        this.metallic = metallic;
        this.f0 = f0;
        this.ao = ao;
        this.emissive = emissive;
        this.height = height;
        this.sss = sss;
        this.porosity = porosity;
        this.metalID = metalID;
    }

    public PBRMaterial copy() {
        return new PBRMaterial(roughness, metallic, f0, ao,
            emissive, height, sss, porosity, metalID);
    }
}
