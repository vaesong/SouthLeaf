package cn.zhinan.uniapp.service;

import cn.zhinan.uniapp.entity.Category;
import cn.zhinan.uniapp.repository.CategoryRepository;
import cn.zhinan.uniapp.common.LongIdGenerator;
import cn.zhinan.uniapp.dto.CategoryDTO;
import cn.zhinan.uniapp.common.Utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class CategoryService {

    @Autowired
    private Utils utils;  // 注入 Utils 实例

    @Autowired
    private CategoryRepository categoryRepository;

    private static final Logger logger = LoggerFactory.getLogger(CategoryService.class);

    /**
     * 获取所有类别及其对应的图片列表
     * @return 包含所有类别信息和图片列表的DTO列表
     */
    public List<CategoryDTO> getAllCategoriesWithImages() {
        logger.info("Fetching all categories with images...");

        List<CategoryDTO> result = new ArrayList<>();
        List<Category> categories = categoryRepository.findAll();

        for (Category category : categories) {
            List<String> images = utils.scanImages(category.getPath());
            result.add(new CategoryDTO(category.getId(), category.getName(), category.getDescription(), images));
        }

        return result;
    }

    /**
     * 根据类别名称查找对应的类别ID
     *
     * @param name 要查找的类别名称
     * @return 对应的类别ID
     * @throws  如果找不到指定名称的类别
     */
    public String findCategoryIdByName(String name) {
        // 根据名称查询 Category
        return categoryRepository.findByName(name)
                .stream()
                .findFirst() // 假设名称唯一，取第一个
                .map(Category::getId)
                .orElseThrow(() -> new IllegalArgumentException("Category with name '" + name + "' does not exist."));
    }

    /**
     * 保存新的类别信息
     * 
     * @param name 类别名称
     * @param description 类别描述
     * @param path 类别对应的图片路径
     * @return 保存后的Category对象
     */
    public Category saveCategory(String name, String description, String path) {
        Category category = new Category();
        category.setId(LongIdGenerator.generateLongId()); // 调用 LongIdGenerator 生成 ID
        category.setName(name);
        category.setDescription(description);
        category.setPath(path);

        return categoryRepository.save(category); // 保存到数据库
    }

    /**
     * 更新现有的类别信息
     * 
     * @param categoryRequest 包含更新信息的Category对象
     * @return 更新后的Category对象
     * @throws  如果指定ID的类别不存在
     */
    public Category partialUpdateCategory(String id, Map<String, Object> updates) {
        // 检查 Category 是否存在
        Category existingCategory = categoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Category with ID " + id + " does not exist."));

        // 动态更新字段
        updates.forEach((key, value) -> {
            switch (key) {
                case "name":
                    existingCategory.setName((String) value);
                    break;
                case "description":
                    existingCategory.setDescription((String) value);
                    break;
                case "path":
                    existingCategory.setPath((String) value);
                    break;
                default:
                    throw new IllegalArgumentException("Field " + key + " is not updatable.");
            }
        });

        // 保存并返回更新后的 Category
        return categoryRepository.save(existingCategory);
    }

    /**
     * 删除指定ID或名称的类别
     * 
     * @param id 要删除的类别的ID，可以为null
     * @param name 要删除的类别的名称，可以为null
     * @throws  如果指定的ID或名称的类别不存在
     */
    public void deleteCategory(String id, String name) {
        if (id != null) {
            // 根据 ID 删除
            if (!categoryRepository.existsById(id)) {
                throw new IllegalArgumentException("Category with id " + id + " does not exist.");
            }
            categoryRepository.deleteById(id);
        } else if (name != null) {
            // 根据 name 删除
            List<Category> categories = categoryRepository.findByName(name);
            if (categories.isEmpty()) {
                throw new IllegalArgumentException("No categories found with name: " + name);
            }
            categoryRepository.deleteAll(categories);
        }
    }
}
