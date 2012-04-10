package org.deuce.transform.commons;


/**
 * Contains a field information.
 *
 * @author Guy Korland
 * @since 1.0
 */
@Exclude
public class Field{
	private final String fieldNameAddress;
	private final String fieldName;

	public Field( String fieldName, String fieldNameAddress) {
		this.fieldName = fieldName;
		this.fieldNameAddress = fieldNameAddress;
	}

	public String getFieldNameAddress() {
		return fieldNameAddress;
	}

	public String getFieldName() {
		return fieldName;
	}
}