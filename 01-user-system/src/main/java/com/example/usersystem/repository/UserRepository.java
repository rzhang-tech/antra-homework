package com.example.usersystem.repository;
import com.example.usersystem.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
//Repository in JPA is interface,not class
@Repository
public interface UserRepository extends JpaRepository<User, Long>{
    //unique.so use optional
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);

    //not unique.List
    List<User> findByUsernameContainingIgnoreCase(String keyword);
}
