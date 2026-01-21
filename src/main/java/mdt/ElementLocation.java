package mdt;

import mdt.persistence.MDTModelLookup;

import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;
import de.fraunhofer.iosb.ilt.faaast.service.model.SubmodelElementIdentifier;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface ElementLocation {
	/**
	 * 본 ElementLocation을 활성화한다.
	 *
	 * @param lookup
	 */
	public void activate(MDTModelLookup lookup);
	
	public String getSubmodelId();
	
	/**
	 * 대상 Element가 포함된 Submodel의 idShort를 반환한다.
	 *
	 * @return	Submodel의 idShort
	 */
	public String getSubmodelIdShort();
	
	/**
	 * 대상 Element의 idShortPath를 반환한다.
	 *
	 * @return	Element의 idShortPath
	 */
	public String getElementPath();
	
	/**
	 * Element의 위치를	표현하는 문자열을 반환한다.
	 *
	 * @return	Element의 위치를 표현하는 문자열
	 */
	public String toStringExpr();
	

	public default SubmodelElementIdentifier toIdentifier() {
		IdShortPath idShortPath = IdShortPath.parse(getElementPath());
		return SubmodelElementIdentifier.builder()
										.submodelId(getSubmodelId())
										.idShortPath(idShortPath)
										.build();
	}
}
