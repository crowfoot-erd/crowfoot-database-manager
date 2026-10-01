# crowfoot-database-manager

Crowfoot ERD 에디터의 **DB 매니저** 서버다. 워크스페이스 커넥션이 가리키는 데이터베이스의 데이터를 웹에서 조회·편집하고 SQL을 실행하는 기능(데이터 브라우저)을 제공한다.

The database manager service of the Crowfoot ERD editor: browse and edit data and run SQL against the databases your workspace connections point to.

## 구조

- Java 21 · Spring Boot. **자기 DB가 없다.** 권한 판정, 접속 정보, 감사 기록은 core(`crowfoot-core-api`)의 내부 API로 처리한다.
- 외부 경로는 `/api/v1/database/**`다. Gateway가 토큰을 검증하고 `X-USER-ID`를 붙여 넘긴다. 구현 경로는 `/database/**`다.
- 요청마다 대상 데이터베이스(MySQL·PostgreSQL)에 접속하고, 끝나면 닫는다. 조회 결과를 저장하지 않는다.

스펙은 docs 리포의 `09-database-manager/00-data-browser.md`가 원천이다.

## 로컬 실행

```sh
mvn spring-boot:run        # 포트 8084, 기본 프로필 local
mvn test                   # 테스트
```

core(8082)가 떠 있어야 요청이 처리된다. 지금은 필요한 시크릿이 없다. 시크릿이 생기면 `.env-local.example`을 복사해 `.env-local`을 만든다.

## 라이선스

Apache License 2.0
