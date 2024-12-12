package cn.zhinan.uniapp.service;

import cn.zhinan.uniapp.entity.Package;
import cn.zhinan.uniapp.repository.PackageRepository;
import cn.zhinan.uniapp.repository.CategoryRepository;
import cn.zhinan.uniapp.common.LongIdGenerator;
import cn.zhinan.uniapp.dto.PackageDTO;
import cn.zhinan.uniapp.common.Utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PackageService {
    @Autowired
    private Utils utils;  // 注入 Utils 实例

    @Autowired
    private PackageRepository packageRepository;
    
    @Autowired
    private CategoryRepository categoryRepository;

    private static final Logger logger = LoggerFactory.getLogger(PackageService.class);

    /**
     * 获取所有套系及其对应的图片列表
     * @return 包含所有套系信息和图片列表的DTO列表
     */
    public List<PackageDTO> getAllPackagesWithImages() {
    logger.info("Fetching all packages with images...");

    return packageRepository.findAll().stream()
        .map(pkg -> {
            List<String> images = utils.scanImages(pkg.getPath()); // 扫描图片路径
            return new PackageDTO(
                pkg.getId(),
                pkg.getTitle(),
                pkg.getCategory() != null ? pkg.getCategory().getName() : null, // 分类名称
                pkg.getDescription(),
                images,
                pkg.getPrice(),
                pkg.getActivityPrice(),
                pkg.getActivateStart(),
                pkg.getActivateEnd(),
                pkg.getServices(),
                pkg.getReminder()
            );
        })
        .collect(Collectors.toList());
    }


    /**
     * 保存新的套系信息
     *
     * @param title 套系标题
     * @param description 套系描述
     * @param categoryId 套系类别ID
     * @param path 套系图片路径
     * @param price 套系原价
     * @param activityPrice 套系活动价格
     * @param activateStart 活动开始日期
     * @param activateEnd 活动结束日期
     * @param services 套系包含的服务
     * @param reminder 套系提醒事项
     * @return 保存后的Package对象
     */
    public Package savePackage(
            String title,
            String description,
            String categoryId,
            String path,
            BigDecimal price,
            BigDecimal activityPrice,
            LocalDate activateStart,
            LocalDate activateEnd,
            List<String> services,
            List<String> reminder
    ) {
        // 验证 categoryId 是否存在
        if (!categoryRepository.existsById(categoryId)) {
            throw new IllegalArgumentException("Invalid categoryId: " + categoryId);
        }

        Package pkg = new Package();
        pkg.setId(LongIdGenerator.generateLongId()); // 使用 LongIdGenerator 生成 ID
        pkg.setTitle(title);
        pkg.setDescription(description);
        pkg.setCategoryId(categoryId);
        pkg.setPath(path);
        pkg.setPrice(price);
        pkg.setActivityPrice(activityPrice);
        pkg.setActivateStart(activateStart);
        pkg.setActivateEnd(activateEnd);
        pkg.setServices(services);
        pkg.setReminder(reminder);

        return packageRepository.save(pkg);
    }

    /**
   * 删除指定ID或标题的套系
   * 
   * @param id 要删除的套系的ID，可以为null
   * @param title 要删除的套系的标题，可以为null
   * @throws  如果指定的ID或标题的套系不存在
   */
    public void deletePackage(String id, String title) {
        if (id != null) {
            // 根据 id 删除
            if (!packageRepository.existsById(id)) {
                throw new IllegalArgumentException("Package with id " + id + " does not exist.");
            }
            packageRepository.deleteById(id);
        } else if (title != null) {
            // 根据 title 删除
            List<Package> packages = packageRepository.findByTitle(title);
            if (packages.isEmpty()) {
                throw new IllegalArgumentException("No packages found with title: " + title);
            }
            packageRepository.deleteAll(packages);
        }
    }

    /**
     * 部分更新套系信息
     * 
     * @param id 要更新的套系ID
     * @param updates 包含需要更新字段的Map
     * @return 更新后的Package对象
     * @throws  如果套系不存在或字段不可更新
     */
    public Package partialUpdatePackage(String id, Map<String, Object> updates) {
        // 检查是否存在
        Package existingPackage = packageRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Package with ID " + id + " does not exist."));

        // 动态更新字段
        updates.forEach((key, value) -> {
            switch (key) {
                case "title":
                    existingPackage.setTitle((String) value);
                    break;
                case "description":
                    existingPackage.setDescription((String) value);
                    break;
                case "categoryId":
                    // 验证 categoryId 是否存在
                    if (!categoryRepository.existsById((String) value)) {
                        throw new IllegalArgumentException("Invalid categoryId: " + value);
                    }
                    existingPackage.setCategoryId((String) value);
                    break;
                case "path":
                    existingPackage.setPath((String) value);
                    break;
                case "price":
                    existingPackage.setPrice(new BigDecimal(value.toString()));
                    break;
                case "activityPrice":
                    existingPackage.setActivityPrice(new BigDecimal(value.toString()));
                    break;
                case "activateStart":
                    existingPackage.setActivateStart(LocalDate.parse((String) value));
                    break;
                case "activateEnd":
                    existingPackage.setActivateEnd(LocalDate.parse((String) value));
                    break;
                case "services":
                    if (value instanceof List) {
                        List<?> rawList = (List<?>) value;
                        List<String> servicesList = rawList.stream()
                            .filter(item -> item instanceof String) // 确保列表中的每个元素是 String
                            .map(Object::toString) // 转换为 String
                            .toList();
                        existingPackage.setServices(servicesList);
                    }
                    break;
                
                case "reminder":
                    if (value instanceof List) {
                        List<?> rawList = (List<?>) value;
                        List<String> reminderList = rawList.stream()
                            .filter(item -> item instanceof String)
                            .map(Object::toString)
                            .toList();
                        existingPackage.setReminder(reminderList);
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Field " + key + " is not updatable.");
            }
        });

        // 保存更新后的对象
        return packageRepository.save(existingPackage);
    }
}
