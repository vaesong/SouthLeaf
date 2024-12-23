package cn.zhinan.uniapp.repository;

import cn.zhinan.uniapp.entity.Category;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, String> {
    // JPA 已经提供了常用的数据库操作方法
    List<Category> findByName(String name);
}
