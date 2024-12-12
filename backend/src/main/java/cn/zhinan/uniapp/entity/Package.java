package cn.zhinan.uniapp.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

@Entity
public class Package {

    @Id
    private String id;
    private String title;
    private String description;
    
    @Column(name = "categoryId") // 映射到数据库的 categoryId 列
    private String categoryId; // 类型与 Category 的 id 保持一致

    @ManyToOne
    @JoinColumn(name = "categoryId", insertable = false, updatable = false) // 外键关联
    private Category category;

    private String path;
    private BigDecimal price;
    private BigDecimal activityPrice;
    private LocalDate activateStart;
    private LocalDate activateEnd;
    private String services; // JSON 格式存储
    private String reminder; // JSON 格式存储

    // 必须包含无参构造函数
    public Package() {}

    public Category getCategory() {
        return category;
    }
    
    public void setCategory(Category category) {
        this.category = category;
    }

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public String getTitle() {
      return title;
    }

    public void setTitle(String title) {
      this.title = title;
    }

    public String getDescription() {
      return description;
    }

    public void setDescription(String description) {
      this.description = description;
    }

    public String getCategoryId() {
      return categoryId;
    }

    public void setCategoryId(String categoryId) {
      this.categoryId = categoryId;
    }

    public String getPath() {
      return path;
    }

    public void setPath(String path) {
      this.path = path;
    }

    public BigDecimal getPrice() {
      return price;
    }

    public void setPrice(BigDecimal price) {
      this.price = price;
    }

    public BigDecimal getActivityPrice() {
      return activityPrice;
    }

    public void setActivityPrice(BigDecimal activityPrice) {
      this.activityPrice = activityPrice;
    }

    public LocalDate getActivateStart() {
      return activateStart;
    }

    public void setActivateStart(LocalDate activateStart) {
      this.activateStart = activateStart;
    }

    public LocalDate getActivateEnd() {
      return activateEnd;
    }

    public void setActivateEnd(LocalDate activateEnd) {
      this.activateEnd = activateEnd;
    }

    // 转换方法
    public List<String> getServices() {
        return services == null ? List.of() : List.of(services.split(","));
    }

    public void setServices(List<String> serviceList) {
        this.services = String.join(",", serviceList);
    }

    public List<String> getReminder() {
        return reminder == null ? List.of() : List.of(reminder.split(","));
    }

    public void setReminder(List<String> reminderList) {
        this.reminder = String.join(",", reminderList);
    }
}

