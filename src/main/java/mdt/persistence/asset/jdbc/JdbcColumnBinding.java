package mdt.persistence.asset.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;

import utils.Preconditions;

import mdt.aas.DataType;
import mdt.aas.DataTypes;
import mdt.persistence.asset.AssetVariableException;


/**
 * 조회 결과의 컬럼 하나와 SubmodelElement 하나 사이의 변환.
 * <p>
 * 대상 요소는 변수의 루트 요소(버퍼, 저장할 값, List의 행 원소 등)에서 {@link #getSubPath() subPath}로 찾는다.
 * 바인딩은 {@link #of(SubmodelElement, String)}로 만들며, 대상 요소의 종류에 따라 변환 방식이 정해진다.
 * <ul>
 *   <li>{@link Property} — 선언된 valueType의 {@link DataType}으로 변환한다. SQL {@code NULL}은 값 없음
 *       ({@code null})이 된다.</li>
 *   <li>그 밖의 SubmodelElement, 또는 {@link DataTypes}가 지원하지 않는 valueType의 Property — 컬럼 값을
 *       MDT value 형식의 JSON 문자열로 주고받는다. SQL {@code NULL}이면 요소를 바꾸지 않는다.</li>
 * </ul>
 * 드라이버 오류는 {@link SQLException}으로, 값 변환 실패는 subPath·컬럼 정보를 담은
 * {@link AssetVariableException}으로 던진다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface JdbcColumnBinding {
	/**
	 * 루트 요소 기준의 대상 요소 경로를 반환한다.
	 *
	 * @return	대상 요소 경로. 루트 자신이면 빈 문자열.
	 */
	public String getSubPath();

	/**
	 * {@code rs}의 현재 행에서 {@code column}번째 값을 읽어 {@code root} 아래 대상 요소에 반영한다.
	 *
	 * @param rs		현재 행에 위치한 조회 결과.
	 * @param column	읽을 컬럼 번호(1부터 시작).
	 * @param root		대상 요소를 찾을 루트 요소.
	 * @throws SQLException	컬럼 값을 읽지 못한 경우.
	 * @throws AssetVariableException	대상 요소가 없거나 값 변환이 실패한 경우.
	 */
	public void read(ResultSet rs, int column, SubmodelElement root) throws SQLException;

	/**
	 * {@code root} 아래 대상 요소의 값을 {@code ps}의 {@code index}번째 파라미터로 설정한다.
	 *
	 * @param ps		파라미터를 설정할 질의.
	 * @param index		파라미터 번호(1부터 시작).
	 * @param root		대상 요소를 찾을 루트 요소.
	 * @throws SQLException	파라미터를 설정하지 못한 경우.
	 * @throws AssetVariableException	대상 요소가 없거나 값 변환이 실패한 경우.
	 */
	public void bind(PreparedStatement ps, int index, SubmodelElement root) throws SQLException;

	/**
	 * 이 바인딩의 대상 요소를 비울 수 있는지 반환한다.
	 * <p>
	 * Property는 값을 {@code null}로 비울 수 있다. JSON으로 주고받는 요소는 비운 상태의 형태가 정해져 있지 않아
	 * 비울 수 없으므로, 조회 결과가 없을 때 값을 비우는 설정({@link NoRecordPolicy#CLEAR})과 함께 쓸 수 없다.
	 *
	 * @return	비울 수 있으면 {@code true}.
	 */
	public boolean isClearable();

	/**
	 * {@code root} 아래 대상 요소의 값을 비운다.
	 * <p>
	 * Property이면 값을 {@code null}로 만든다. 비울 수 없는 요소({@link #isClearable()})는 바꾸지 않는다.
	 *
	 * @param root	대상 요소를 찾을 루트 요소.
	 * @throws AssetVariableException	대상 요소가 없는 경우.
	 */
	public void clear(SubmodelElement root);

	/**
	 * {@code prototype}에서 {@code subPath}의 요소를 찾아, 그 종류에 맞는 바인딩을 만든다.
	 *
	 * @param prototype	대상 요소를 찾을 루트 요소. 이후 {@code read}/{@code bind}에 넘길 루트와 구조가 같아야 한다.
	 * @param subPath	루트 요소 기준의 대상 요소 경로. 루트 자신이면 빈 문자열.
	 * @return	생성된 바인딩.
	 * @throws AssetVariableException	경로에 요소가 없는 경우.
	 */
	public static JdbcColumnBinding of(SubmodelElement prototype, String subPath) {
		Preconditions.checkNotNullArgument(prototype, "prototype is null");
		Preconditions.checkNotNullArgument(subPath, "subPath is null");

		SubmodelElement target = AbstractJdbcColumnBinding.traverse(prototype, subPath);
		if ( target instanceof Property prop ) {
			DataType<?> type = DataTypes.fromAas4jDatatype(prop.getValueType());
			if ( type != null ) {
				return new PropertyColumnBinding(subPath, type);
			}
		}
		return new JsonColumnBinding(subPath);
	}
}
