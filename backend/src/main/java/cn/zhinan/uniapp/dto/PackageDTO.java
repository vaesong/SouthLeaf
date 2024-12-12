package cn.zhinan.uniapp.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class PackageDTO {

    private String id;
    private String title;
    private String description;
    private String categoryId;
    private List<String> images;
    private BigDecimal price;
    private BigDecimal activityPrice;
    private LocalDate activateStart;
    private LocalDate activateEnd;
    private List<String> services; // JSON 格式存储
    private List<String> reminder; // JSON 格式存储

    // 必须包含无参构造函数
    public PackageDTO() {}

    // 有参构造函数
    public PackageDTO(String id, 
                      String title, 
                      String description, 
                      String categoryId, 
                      List<String> images, 
                      BigDecimal price, 
                      BigDecimal activityPrice, 
                      LocalDate activateStart, 
                      LocalDate activateEnd, 
                      List<String> services, 
                      List<String> reminder){
      this.id = id;
      this.title = title;
      this.description = description;
      this.categoryId = categoryId;
      this.images = images;
      this.price = price;
      this.activityPrice = activityPrice;
      this.activateStart = activateStart;
      this.activateEnd = activateEnd;
      this.services = services;
      this.reminder = reminder;
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

    public List<String> getImages() {
      return images;
    }

    public void setImages(List<String> images) {
      this.images = images;
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

    public List<String> getServices() {
      return services;
    }

    public void setServices(List<String> services) {
      this.services = services;
    }

    public List<String> getReminder() {
      return reminder;
    }

    public void setReminder(List<String> reminder) {
      this.reminder = reminder;
    }
}
