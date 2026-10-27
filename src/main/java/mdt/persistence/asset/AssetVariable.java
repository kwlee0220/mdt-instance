package mdt.persistence.asset;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;

import mdt.ElementLocation;
import mdt.model.sm.SubmodelUtils;
import mdt.model.sm.value.ElementValues;
import mdt.persistence.MDTModelLookup;


/**
 * 외부 설비 (Asset)가 생산하는 데이터와 연계하기 위한 인터페이스를 정의한다.
 * <p>
 * {@code AssetVariable}은 AAS의 단일 SubmodelElement와 바인딩되고,
 * 이는 {@link #initialize(MDTModelLookup)}를 통해 설정된다.
 * {@link #read()}를 통해 설정 설비에서 읽은 데이터가 SubmodelElement에 저장되고,
 * {@link #update(SubmodelElement)}를 통해 SubmodelElement의 값이 설정 설비에 전달된다.
 * <p>
 * 일부 설비의 경우에는 설비로 부터 데이터 읽기만 가능하고, 데이터 쓰기는 불가능한 경우가 있다.
 * 이 경우에는 {@link #isUpdatable()}가 {@code false}를 반환한다.
 * <p>
 * 외부 연결 등 자원을 사용하는 구현은 {@link #close()}에서 이를 반납한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface AssetVariable extends AutoCloseable {
	/**
	 * 대상 설비의 데이터를 담당하는 AAS내의 SubmodelElement의 위치를 반환한다.
	 * 
	 * @return ElementLocation.
	 */
	public ElementLocation getElementLocation();
	
	/**
	 * 본 {@code AssetVariable}을 통해 SubmodelElement를 읽을 수 있는지 여부를 반환한다.
	 * 
	 * @return 읽기 여부.
	 */
	public boolean isReadable();
	
	/**
	 * 본 {@code AssetVariable}을 통해 SubmodelElement를 갱신할 수 있는지 여부를 반환한다.
	 * 
	 * @return 갱신 여부.
	 */
	public boolean isUpdatable();
	
	/**
     * 이 {@code AssetVariable}과 AAS내 SubmodelElement를 연결한다.
     * 
     * @param lookup	연결할 SubmodelElement 접속에 사용할 lookup 객체.
     */
	public void initialize(MDTModelLookup lookup);

	/**
	 * 이 {@code AssetVariable}이 사용하는 자원(외부 연결, 백그라운드 작업 등)을 반납한다.
	 * <p>
	 * 영속성 계층이 중지될 때와, {@link #initialize(MDTModelLookup)}가 실패했을 때 호출된다.
	 * 따라서 초기화가 완료되지 않은 상태에서도 호출될 수 있고, 여러 번 호출되어도 안전해야 한다.
	 * 기본 구현은 아무 일도 하지 않는다.
	 */
	@Override
	public default void close() { }

	/**
	 * 연결된 SubmodelElement의 최신 값을 읽어 반환한다.
	 * <p>
	 * 연결된 datasource에서 최신 값을 읽어 최신의 SubmodelElement를 구성하여 반환한다.
	 * <p>
	 * 반환값은 호출할 때마다 새로 만든 객체이며 내부 캐시와 공유되지 않는다. 따라서 호출자는
	 * 반환값을 변경하거나 보관해도 된다. 구현은 내부 캐시를 그대로 반환해서는 안 되며,
	 * 캐시를 보호하는 임계 구역 안에서 복사본을 만들어 반환해야 한다.
	 *
	 * @return	SubmodelElement
	 * @throws AssetVariableException	연결된 datasource에서 값 읽기가 실패한 경우.
	 */
	public SubmodelElement read() throws AssetVariableException;

	/**
	 * 주어진 값을 연결된 datasource에 저장한다.
	 * <p>
	 * 구현은 전달된 {@code newElement}를 내부 캐시로 보관해서는 안 된다(필요하면 복사본을 보관한다).
	 * 따라서 호출자는 이 메서드 호출 이후에도 {@code newElement}를 계속 사용할 수 있다.
	 *
	 * @param newElement	저장할 값.
	 * @throws AssetVariableException	연결된 datasource에 값 저장이 실패한 경우.
	 */
	public void update(SubmodelElement newElement) throws AssetVariableException;

	/**
	 * 연결된 SubmodelElement 중 {@code relPath}가 가리키는 하위 요소의 값만 {@code part}의 값으로 바꿔 저장한다.
	 * <p>
	 * 현재 값을 읽어 하위 요소의 값을 바꾼 뒤 전체를 저장한다. 기본 구현은 {@link #read()}와
	 * {@link #update(SubmodelElement)}를 차례로 호출하므로 원자적이지 않다. 동시에 여러 갱신이
	 * 일어날 수 있는 구현은 이 메소드를 재정의하여 읽기-수정-저장을 하나의 임계 구역에서 수행해야
	 * 한다. 그렇지 않으면 동시에 들어온 갱신 중 하나가 사라질 수 있다.
	 *
	 * @param relPath	이 {@code AssetVariable}의 SubmodelElement를 기준으로 한 하위 요소의 상대 idShort 경로.
	 * @param part		하위 요소에 반영할 값을 가진 SubmodelElement.
	 * @throws AssetVariableException	읽기 또는 저장이 실패한 경우.
	 */
	public default void updatePart(String relPath, SubmodelElement part) throws AssetVariableException {
		SubmodelElement whole = read();
		ElementValues.update(SubmodelUtils.traverse(whole, relPath), ElementValues.getValue(part));
		update(whole);
	}
	
	public default boolean contains(String elementPath) {
		return isPathPrefix(getElementLocation().getElementPath(), elementPath);
	}

	public default boolean isContained(String elementPath) {
		return isPathPrefix(elementPath, getElementLocation().getElementPath());
	}

	public default boolean overlaps(String elementPath) {
		return contains(elementPath) || isContained(elementPath);
	}

	/**
	 * {@code prefix}가 {@code path}의 idShort 경로 접두어인지 여부를 반환한다.
	 * <p>
	 * 단순 문자열 접두어가 아니라 경로 세그먼트 경계({@code '.'} 또는 {@code '['})를 기준으로 판정하기 때문에
	 * {@code "DataInfo.Value1"}은 {@code "DataInfo.Value10"}의 접두어가 아니다.
	 * 두 경로가 동일한 경우에도 {@code true}를 반환한다.
	 *
	 * @param prefix	접두어 후보 idShort 경로.
	 * @param path		대상 idShort 경로.
	 * @return			{@code prefix}가 {@code path}의 경로 접두어이면 {@code true}.
	 */
	public static boolean isPathPrefix(String prefix, String path) {
		return SubmodelUtils.isIdShortPathPrefix(prefix, path);
	}
}
