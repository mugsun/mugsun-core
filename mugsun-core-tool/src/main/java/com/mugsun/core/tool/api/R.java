package com.mugsun.core.tool.api;

import java.io.Serializable;

/**
 * 统一响应结构
 *
 * @param <T> 数据类型
 */
public class R<T> implements Serializable {

	private int code;
	private boolean success;
	private T data;
	private String msg;
	/** 数据类型标记：为 "ENCRYPT" 时表示 data 为密文（接口加解密），普通响应为 null */
	private String dataType;

	public R() {
	}

	private R(int code, boolean success, T data, String msg) {
		this.code = code;
		this.success = success;
		this.data = data;
		this.msg = msg;
	}

	public static <T> R<T> data(T data) {
		return new R<>(ResultCode.SUCCESS.getCode(), true, data, ResultCode.SUCCESS.getMsg());
	}

	public static <T> R<T> success(String msg) {
		return new R<>(ResultCode.SUCCESS.getCode(), true, null, msg);
	}

	public static <T> R<T> fail(String msg) {
		return new R<>(ResultCode.FAILURE.getCode(), false, null, msg);
	}

	public static <T> R<T> fail(ResultCode resultCode) {
		return new R<>(resultCode.getCode(), false, null, resultCode.getMsg());
	}

	public static <T> R<T> fail(ResultCode resultCode, String msg) {
		return new R<>(resultCode.getCode(), false, null, msg);
	}

	public int getCode() {
		return code;
	}

	public void setCode(int code) {
		this.code = code;
	}

	public boolean isSuccess() {
		return success;
	}

	public void setSuccess(boolean success) {
		this.success = success;
	}

	public T getData() {
		return data;
	}

	public void setData(T data) {
		this.data = data;
	}

	public String getMsg() {
		return msg;
	}

	public void setMsg(String msg) {
		this.msg = msg;
	}

	public String getDataType() {
		return dataType;
	}

	public void setDataType(String dataType) {
		this.dataType = dataType;
	}
}
