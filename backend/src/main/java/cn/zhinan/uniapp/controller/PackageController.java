package cn.zhinan.uniapp.controller;

import cn.zhinan.uniapp.service.PackageService;
import cn.zhinan.uniapp.common.ApiResponse;
import cn.zhinan.uniapp.dto.PackageDTO;
import cn.zhinan.uniapp.entity.Package;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/packages")
public class PackageController {
    @Autowired
    private PackageService packageService;

    @GetMapping("/getAllPackages")
    public ApiResponse<List<PackageDTO>> getAllCategories() {
        List<PackageDTO> packages = packageService.getAllPackagesWithImages();
        return ApiResponse.success(packages);
    }

    @PostMapping("/add")
    public ApiResponse<Package> addPackage(@RequestBody Package packageRequest) {
        Package newPackage = packageService.savePackage(
            packageRequest.getTitle(),
            packageRequest.getDescription(),
            packageRequest.getCategoryId(),
            packageRequest.getPath(),
            packageRequest.getPrice(),
            packageRequest.getActivityPrice(),
            packageRequest.getActivateStart(),
            packageRequest.getActivateEnd(),
            packageRequest.getServices(),
            packageRequest.getReminder()
        );
        return ApiResponse.success(newPackage);
    }

    @DeleteMapping("/delete")
    public ApiResponse<String> deletePackage(@RequestParam(required = false) String id, 
                                            @RequestParam(required = false) String title) {
        if (id == null && title == null) {
            throw new IllegalArgumentException("Either 'id' or 'title' must be provided for deletion.");
        }
        packageService.deletePackage(id, title);
        return ApiResponse.success("Package deleted successfully");
    }

    @PatchMapping("/update/{id}")
    public ApiResponse<Package> partialUpdatePackage(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) {
            throw new IllegalArgumentException("At least one field must be provided for update.");
        }
        Package updatedPackage = packageService.partialUpdatePackage(id, updates);
        return ApiResponse.success(updatedPackage);
    }

}
