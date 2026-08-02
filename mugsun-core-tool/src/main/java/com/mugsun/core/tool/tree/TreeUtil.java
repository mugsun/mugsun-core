package com.mugsun.core.tool.tree;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 树构建工具
 */
public class TreeUtil {

	private TreeUtil() {
	}

	/**
	 * 将扁平列表构建为树（带环保护：父指针成环的节点随环整体不可见，杜绝 StackOverflow 持续 500）
	 *
	 * @param nodes        全部节点
	 * @param rootParentId 根节点的父 ID（如顶级为 0）
	 */
	public static <T extends INode<T>> List<T> build(List<T> nodes, Long rootParentId) {
		return build(nodes, rootParentId, new java.util.HashSet<>());
	}

	/** 递归构建：path 记录当前路径上的节点 id，重复进入即环，跳过 */
	private static <T extends INode<T>> List<T> build(List<T> nodes, Long parentId, java.util.Set<Long> path) {
		List<T> roots = new ArrayList<>();
		for (T node : nodes) {
			if (Objects.equals(node.getParentId(), parentId) && path.add(node.getId())) {
				node.setChildren(build(nodes, node.getId(), path));
				path.remove(node.getId());
				roots.add(node);
			}
		}
		return roots;
	}
}
