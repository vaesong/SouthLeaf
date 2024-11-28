package cn.zhinan.uniapp.dto;

import java.util.List;

public class CategoryDTO {
    private String id;
    private String name;
    private String description;
    private List<String> images;

    // 必须有无参构造函数
    public CategoryDTO() {
    }

    // 全参构造函数
    public CategoryDTO(String id, String name, String description, List<String> images) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.images = images;
    }

    // Getter 和 Setter 方法
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getImages() {
        return images;
    }

    public void setImages(List<String> images) {
        this.images = images;
    }
}
