package cn.zhinan.uniapp.controller;

import cn.zhinan.uniapp.service.CategoryService;
import cn.zhinan.uniapp.common.ApiResponse;
import cn.zhinan.uniapp.dto.CategoryDTO;
import cn.zhinan.uniapp.entity.Category;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    @PostMapping("/add")
    public ApiResponse<Category> addCategory(@RequestBody Category categoryRequest) {
        Category newCategory = categoryService.saveCategory(
            categoryRequest.getName(),
            categoryRequest.getDescription(),
            categoryRequest.getPath()
        );
        return ApiResponse.success(newCategory);
    }

    @PutMapping("/update")
    public ApiResponse<Category> updateCategory(@RequestBody Category categoryRequest) {
        Category updatedCategory = categoryService.updateCategory(categoryRequest);
        return ApiResponse.success(updatedCategory);
    }


    @DeleteMapping("/delete/{id}")
    public ApiResponse<String> deleteCategory(@PathVariable String id) {
        categoryService.deleteCategory(id);
        return ApiResponse.success("Category with ID " + id + " deleted successfully!");
    }


    @GetMapping("/error")
    public ApiResponse<Object> testError() {
        System.out.println("Handling /test/error");
        throw new IllegalArgumentException("This is a test error!");
    }

}
