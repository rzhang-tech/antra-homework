package com.example.book.repository;

import com.example.book.entity.StockReservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StockReservationRepository extends JpaRepository<StockReservation, UUID> {
}
