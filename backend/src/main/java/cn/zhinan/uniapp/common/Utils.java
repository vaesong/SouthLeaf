package cn.zhinan.uniapp.common;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Utils {

    @Value("${app.base-url}")
    private String baseUrl; // 后端服务的基地址

    @Value("${spring.web.resources.static-locations}")
    private String staticLocations; // 读取静态资源路径配置

    /**
     * 补全相对URL为绝对URL
     * @param relativePath 相对路径
     * @return 绝对路径
     */
    public String buildAbsoluteUrl(String relativePath) {
        return baseUrl + relativePath;
    }

    /**
     * 扫描指定文件夹路径下的图片文件
     * @param folderPath 相对于静态资源基础路径的文件夹路径
     * @return 图片文件路径列表
     */
    public List<String> scanImages(String folderPath) {
        List<String> images = new ArrayList<>();
        String basePath = resolveBasePath(staticLocations); // 动态解析基础路径
        File folder = new File(basePath + folderPath);

        if (folder.exists() && folder.isDirectory()) {
            for (File file : folder.listFiles()) {
                if (isImage(file)) {
                    images.add(baseUrl + folderPath + "/" + file.getName());
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
}