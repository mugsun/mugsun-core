package com.mugsun.core.mybatis.base;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体基类：审计字段与逻辑删除只允许数据库侧填充，请求体传入的值必须在落库前被剥离。
 */
class BaseEntityTest {

	/** 业务实体替身 */
	static class Demo extends BaseEntity {
		private String name;

		String getName() {
			return name;
		}

		void setName(String name) {
			this.name = name;
		}
	}

	private Demo forged() {
		Demo demo = new Demo();
		demo.setName("业务字段");
		demo.setCreateTime(LocalDateTime.of(2000, 1, 1, 0, 0));
		demo.setUpdateTime(LocalDateTime.of(2000, 1, 1, 0, 0));
		demo.setIsDeleted(1);
		return demo;
	}

	@Test
	@DisplayName("insert 前清洗剥离伪造的审计时间与逻辑删除标记")
	void sanitizeForInsertStripsAuditFields() {
		Demo demo = forged();

		demo.sanitizeForInsert();

		assertThat(demo.getCreateTime()).isNull();
		assertThat(demo.getUpdateTime()).isNull();
		assertThat(demo.getIsDeleted()).isNull();
	}

	@Test
	@DisplayName("清洗不动业务字段与主键")
	void sanitizeKeepsBusinessFieldsAndId() {
		Demo demo = forged();
		demo.setId(1957432109876543210L);

		demo.sanitizeForInsert();

		assertThat(demo.getName()).isEqualTo("业务字段");
		assertThat(demo.getId()).isEqualTo(1957432109876543210L);
	}

	@Test
	@DisplayName("update 前清洗与 insert 同口径")
	void sanitizeForUpdateMatchesInsert() {
		Demo demo = forged();

		demo.sanitizeForUpdate();

		assertThat(demo.getCreateTime()).isNull();
		assertThat(demo.getUpdateTime()).isNull();
		assertThat(demo.getIsDeleted()).isNull();
	}

	@Test
	@DisplayName("重复清洗幂等，不抛异常")
	void sanitizeIsIdempotent() {
		Demo demo = forged();

		demo.sanitizeForInsert();
		demo.sanitizeForInsert();

		assertThat(demo.getIsDeleted()).isNull();
	}

	@Test
	@DisplayName("主键标注雪花生成器，审计列声明数据库侧填充，逻辑删除列被标记")
	void columnAnnotationsStayInPlace() throws Exception {
		assertThat(BaseEntity.class.getDeclaredField("id").getAnnotation(Id.class)).isNotNull();

		Column createTime = BaseEntity.class.getDeclaredField("createTime").getAnnotation(Column.class);
		assertThat(createTime.onInsertValue()).isEqualTo("now()");

		Column updateTime = BaseEntity.class.getDeclaredField("updateTime").getAnnotation(Column.class);
		assertThat(updateTime.onInsertValue()).isEqualTo("now()");
		assertThat(updateTime.onUpdateValue()).isEqualTo("now()");

		Column isDeleted = BaseEntity.class.getDeclaredField("isDeleted").getAnnotation(Column.class);
		assertThat(isDeleted.isLogicDelete()).isTrue();
	}
}
