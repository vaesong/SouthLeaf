package cn.zhinan.uniapp.controller;

import cn.zhinan.uniapp.service.CategoryService;
import cn.zhinan.uniapp.common.ApiResponse;
import cn.zhinan.uniapp.dto.CategoryDTO;
import cn.zhinan.uniapp.entity.Category;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    @Autowired
    private CategoryService categoryService;

    @GetMapping("/getAllCategories")
    public ApiResponse<List<CategoryDTO>> getAllCategories() {
        List<CategoryDTO> categories = categoryService.getAllCategoriesWithImages();
        return ApiResponse.success(categories);
    }

    @GetMapping("/findIdByName")
    public ApiResponse<String> findCategoryIdByName(@RequestParam String name) {
        String categoryId = categoryService.findCategoryIdByName(name);
        return ApiResponse.success(categoryId);
    }


    @PostMapping("/add")
    public ApiResponse<Category> addCategory(@RequestBody Category categoryRequest) {
        Category newCategory = categoryService.saveCategory(
            categoryRequest.getName(),
            categoryRequest.getDescription(),
            categoryRequest.getPath()
        );
        return ApiResponse.success(newCategory);
    }

    @PatchMapping("/update/{id}")
    public ApiResponse<Category> updateCategory(
            @PathVariable String id,
            @RequestBody Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) {
            throw new IllegalArgumentException("No fields provided for update.");
        }
        Category updatedCategory = categoryService.partialUpdateCategory(id, updates);
        return ApiResponse.success(updatedCategory);
    }



    @DeleteMapping("/delete")
    public ApiResponse<String> deleteCategory(@RequestParam(required = false) String id,
                                            @RequestParam(required = false) String name) {
        if (id == null && name == null) {
            throw new IllegalArgumentException("Either 'id' or 'name' must be provided for deletion.");
        }
        categoryService.deleteCategory(id, name);
        return ApiResponse.success("Category deleted successfully!");
    }

}
