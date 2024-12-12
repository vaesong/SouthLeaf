package cn.zhinan.uniapp.service;

import cn.zhinan.uniapp.common.Utils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SwipperService {

    @Autowired
    private Utils utils;  // 注入 Utils 实例

    private String SwipperPath = "/轮播区";
    /**
     * 获取轮播区图片
     * @param categoryPath 轮播区的路径
     * @return 图片路径列表（绝对路径）
     */
    public List<String> getSwipperImages() {
        List<String> images = utils.scanImages(SwipperPath);

        return images;
    }
}
