package cn.zhinan.uniapp.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import cn.zhinan.uniapp.common.ApiResponse;
import cn.zhinan.uniapp.service.SwipperService;

@RestController
@RequestMapping("/api/swipper")
public class SwipperController {

    @Autowired
    private SwipperService SwipperService;

    /**
     * 获取轮播区图片
     * @param
     * @return 图片路径列表
     */
    @GetMapping("/getSwipperImages")
    public ApiResponse<List<String>> getAllSwipperImages() {
        List<String> SwipperImages = SwipperService.getSwipperImages();
        return ApiResponse.success(SwipperImages);
    }
}

