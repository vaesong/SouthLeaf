package cn.zhinan.uniapp.service;

import cn.zhinan.uniapp.entity.Category;
import cn.zhinan.uniapp.repository.CategoryRepository;
import cn.zhinan.uniapp.common.LongIdGenerator;
import cn.zhinan.uniapp.dto.CategoryDTO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@Service
public class CategoryService {

    @Value("${spring.web.resources.static-locations}")
    private String staticLocations; // 读取静态资源路径配置

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
            List<String> images = scanImages(category.getPath());
            result.add(new CategoryDTO(category.getId(), category.getName(), category.getDescription(), images));
        }

        return result;
    }

    /**
     * 扫描指定文件夹路径下的图片文件
     * @param folderPath 相对于静态资源基础路径的文件夹路径
     * @return 图片文件路径列表
     */
    private List<String> scanImages(String folderPath) {
        List<String> images = new ArrayList<>();
        String basePath = resolveBasePath(staticLocations); // 动态解析基础路径
        File folder = new File(basePath + folderPath);

        if (folder.exists() && folder.isDirectory()) {
            for (File file : folder.listFiles()) {
                if (isImage(file)) {
                    images.add(folderPath + "/" + file.getName());
                }
            }
        } else {
            System.out.println("Folder not found: " + folder.getAbsolutePath());
        }
        return images;
    }

    /**
     * 解析静态资源的基础路径
     * @param staticLocations 配置的静态资源路径
     * @return 解析后的基础路径
     */
    private String resolveBasePath(String staticLocations) {
        // 如果配置中有多个路径，用第一个作为 basePath
        if (staticLocations.contains(",")) {
            return staticLocations.split(",")[0].replace("file:", "").replace("classpath:", "").trim();
        }
        return staticLocations.replace("file:", "").replace("classpath:", "").trim();
    }

    /**
     * 判断给定文件是否为图片
     * @param file 要检查的文件
     * @return 如果文件是图片则返回true，否则返回false
     */
    private boolean isImage(File file) {
        String[] imageExtensions = {".jpg", ".jpeg", ".png", ".gif", ".bmp"};
        String fileName = file.getName().toLowerCase();

        for (String extension : imageExtensions) {
            if (fileName.endsWith(extension)) {
                return true;
            }
        }
        return false;
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
    public Category updateCategory(Category categoryRequest) {
        // 查找现有的 Category
        Category existingCategory = categoryRepository.findById(categoryRequest.getId())
                .orElseThrow(() -> new IllegalArgumentException("Category not found with ID: " + categoryRequest.getId()));
        
        // 更新字段
        existingCategory.setName(categoryRequest.getName());
        existingCategory.setDescription(categoryRequest.getDescription());
        existingCategory.setPath(categoryRequest.getPath());

        // 保存并返回更新后的 Category
        return categoryRepository.save(existingCategory);
    }

    /**
     * 删除指定ID的类别
     * 
     * @param id 要删除的类别的ID
     * @throws  如果指定ID的类别不存在
     */
    public void deleteCategory(String id) {
        // 检查 Category 是否存在
        if (!categoryRepository.existsById(id)) {
            throw new IllegalArgumentException("Category not found with ID: " + id);
        }

        // 删除 Category
        categoryRepository.deleteById(id);
    }
}
