package cn.zhinan.uniapp.repository;

import cn.zhinan.uniapp.entity.Package;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PackageRepository extends JpaRepository<Package, String> {
    // JPA 已经提供了常用的数据库操作方法
    List<Package> findByTitle(String title);
}
