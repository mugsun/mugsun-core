package com.mugsun.core.mybatis.base;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.core.keygen.KeyGenerators;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 实体基类：雪花主键 + 审计时间 + 逻辑删除
 */
public class BaseEntity implements Serializable {

	/** 雪花主键 */
	@Id(keyType = KeyType.Generator, value = KeyGenerators.flexId)
	private Long id;

	/** 创建时间（插入时数据库 now() 填充） */
	@Column(onInsertValue = "now()")
	private LocalDateTime createTime;

	/** 更新时间（插入/更新时数据库 now() 填充） */
	@Column(onInsertValue = "now()", onUpdateValue = "now()")
	private LocalDateTime updateTime;

	/** 逻辑删除（0 正常 / 1 删除） */
	@Column(isLogicDelete = true)
	private Integer isDeleted;

	/**
	 * 服务端清洗（insert 前调用）：剥离请求体伪造的审计字段——create_time/is_deleted 等
	 * 由数据库与填充器负责，客户端输入一律不信任（MyBatis-Flex update 的 SET 排除集不含这些列）。
	 */
	public void sanitizeForInsert() {
		this.createTime = null;
		this.updateTime = null;
		this.isDeleted = null;
	}

	/** 服务端清洗（update 前调用）：同 {@link #sanitizeForInsert()}，防请求体篡改审计时间与逻辑删除 */
	public void sanitizeForUpdate() {
		sanitizeForInsert();
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public LocalDateTime getCreateTime() {
		return createTime;
	}

	public void setCreateTime(LocalDateTime createTime) {
		this.createTime = createTime;
	}

	public LocalDateTime getUpdateTime() {
		return updateTime;
	}

	public void setUpdateTime(LocalDateTime updateTime) {
		this.updateTime = updateTime;
	}

	public Integer getIsDeleted() {
		return isDeleted;
	}

	public void setIsDeleted(Integer isDeleted) {
		this.isDeleted = isDeleted;
	}
}
