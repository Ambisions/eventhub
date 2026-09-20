package com.eventhub.inventory.repository;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("event_inventory")
public class InventoryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Integer totalStock;
    private Integer availableStock;
    private Integer lockedStock;
    private Integer soldStock;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Integer getTotalStock() { return totalStock; }
    public void setTotalStock(Integer totalStock) { this.totalStock = totalStock; }
    public Integer getAvailableStock() { return availableStock; }
    public void setAvailableStock(Integer availableStock) { this.availableStock = availableStock; }
    public Integer getLockedStock() { return lockedStock; }
    public void setLockedStock(Integer lockedStock) { this.lockedStock = lockedStock; }
    public Integer getSoldStock() { return soldStock; }
    public void setSoldStock(Integer soldStock) { this.soldStock = soldStock; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
