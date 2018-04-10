/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * VM NIC
 */
@Getter
@AllArgsConstructor
public class VmNetwork implements Serializable {

	/**
	 * SID
	 */
	private static final long serialVersionUID = 1L;

	/**
	 * The network tag. Can be public, private, <code>null</code> or something else.
	 */
	private String type;

	/**
	 * The resolved IP.
	 */
	private String ip;

	/**
	 * The optional DNS.
	 */
	private String dns;

}
