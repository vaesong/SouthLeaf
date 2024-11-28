package cn.zhinan.uniapp.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class Category {
    @Id
    private String id;

    private String name;
    private String description; // 添加 description 字段

    private String path;

    // 必须包含无参构造函数
    public Category() {}
    
    // Getters and Setters
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

    public String getDescription() { // 添加 getDescription 方法
        return description;
    }

    public void setDescription(String description) { // 添加 setDescription 方法
        this.description = description;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }
}
