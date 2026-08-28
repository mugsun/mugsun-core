package com.mugsun.core.tool.tree;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 树构建：菜单/部门树的公共入口。重点守住父指针成环时不得栈溢出（历史上导致接口持续 500）。
 */
class TreeUtilTest {

	/** 最小节点实现 */
	static class Node implements INode<Node> {
		private final Long id;
		private final Long parentId;
		private List<Node> children = new ArrayList<>();

		Node(Long id, Long parentId) {
			this.id = id;
			this.parentId = parentId;
		}

		@Override
		public Long getId() {
			return id;
		}

		@Override
		public Long getParentId() {
			return parentId;
		}

		@Override
		public List<Node> getChildren() {
			return children;
		}

		@Override
		public void setChildren(List<Node> children) {
			this.children = children;
		}
	}

	private static Node node(long id, long parentId) {
		return new Node(id, parentId);
	}

	@Test
	@DisplayName("按父子关系构建多层树，兄弟顺序与入参顺序一致")
	void buildsNestedTree() {
		List<Node> flat = Arrays.asList(node(1, 0), node(2, 0), node(11, 1), node(12, 1), node(111, 11));

		List<Node> roots = TreeUtil.build(flat, 0L);

		assertThat(roots).extracting(Node::getId).containsExactly(1L, 2L);
		assertThat(roots.get(0).getChildren()).extracting(Node::getId).containsExactly(11L, 12L);
		assertThat(roots.get(0).getChildren().get(0).getChildren()).extracting(Node::getId).containsExactly(111L);
		assertThat(roots.get(1).getChildren()).isEmpty();
	}

	@Test
	@DisplayName("空列表返回空树")
	void emptyInput() {
		assertThat(TreeUtil.build(Collections.<Node>emptyList(), 0L)).isEmpty();
	}

	@Test
	@DisplayName("根父 ID 不匹配任何节点时返回空树（不误把孤儿提为根）")
	void noMatchingRootParent() {
		List<Node> flat = Arrays.asList(node(11, 1), node(12, 1));

		assertThat(TreeUtil.build(flat, 0L)).isEmpty();
	}

	@Test
	@DisplayName("支持任意根父 ID，可从子树中间截取")
	void buildsSubtreeFromGivenParent() {
		List<Node> flat = Arrays.asList(node(1, 0), node(11, 1), node(111, 11));

		List<Node> roots = TreeUtil.build(flat, 1L);

		assertThat(roots).extracting(Node::getId).containsExactly(11L);
		assertThat(roots.get(0).getChildren()).extracting(Node::getId).containsExactly(111L);
	}

	@Test
	@DisplayName("自指节点不会无限递归，且不出现在自身子级中")
	void selfReferenceDoesNotRecurseForever() {
		List<Node> flat = Arrays.asList(node(1, 0), node(2, 2));

		List<Node> roots = TreeUtil.build(flat, 0L);

		assertThat(roots).extracting(Node::getId).containsExactly(1L);
	}

	@Test
	@DisplayName("父指针成环（1→2→1）不抛栈溢出，成环节点整体不可见")
	void cycleIsSkippedInsteadOfOverflow() {
		// 1 的父是 2、2 的父是 1：两者都不挂在根下，构树结果为空但不得崩
		List<Node> flat = Arrays.asList(node(1, 2L), node(2, 1L));

		assertThatCode(() -> {
			List<Node> roots = TreeUtil.build(flat, 0L);
			assertThat(roots).isEmpty();
		}).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("环挂在正常子树下时，只截断环，正常分支仍可见")
	void cycleUnderValidBranchTruncatesOnlyCycle() {
		// 1 为根，2 挂 1；3 与 4 互为父子成环
		List<Node> flat = Arrays.asList(node(1, 0), node(2, 1), node(3, 4L), node(4, 3L));

		List<Node> roots = TreeUtil.build(flat, 0L);

		assertThat(roots).extracting(Node::getId).containsExactly(1L);
		assertThat(roots.get(0).getChildren()).extracting(Node::getId).containsExactly(2L);
	}

	@Test
	@DisplayName("同一父下的兄弟互不干扰（路径集合按层回退）")
	void siblingsAreIndependent() {
		List<Node> flat = Arrays.asList(node(1, 0), node(11, 1), node(12, 1), node(121, 12), node(111, 11));

		List<Node> roots = TreeUtil.build(flat, 0L);

		List<Node> children = roots.get(0).getChildren();
		assertThat(children).extracting(Node::getId).containsExactly(11L, 12L);
		assertThat(children.get(0).getChildren()).extracting(Node::getId).containsExactly(111L);
		assertThat(children.get(1).getChildren()).extracting(Node::getId).containsExactly(121L);
	}

	@Test
	@DisplayName("parentId 为 null 的节点按 null 根构建")
	void nullParentIdTreatedAsRoot() {
		List<Node> flat = Arrays.asList(new Node(1L, null), node(11, 1));

		List<Node> roots = TreeUtil.build(flat, null);

		assertThat(roots).extracting(Node::getId).containsExactly(1L);
		assertThat(roots.get(0).getChildren()).extracting(Node::getId).containsExactly(11L);
	}

	@Test
	@DisplayName("深链（3000 层）不栈溢出")
	void deepChainDoesNotOverflow() {
		List<Node> flat = new ArrayList<>();
		flat.add(node(1, 0));
		for (long i = 2; i <= 3000; i++) {
			flat.add(node(i, i - 1));
		}

		assertThatCode(() -> TreeUtil.build(flat, 0L)).doesNotThrowAnyException();
	}
}
