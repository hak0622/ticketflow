
```
blog
├─ .claude
│  └─ settings.local.json
├─ CLAUDE.md
├─ TODO.md
├─ docs
│  ├─ api.md
│  ├─ architecture.md
│  ├─ flow.md
│  └─ testing.md
├─ frontend
│  ├─ README.md
│  ├─ dist
│  │  ├─ assets
│  │  │  ├─ index-DBLijPFt.js
│  │  │  ├─ index-qZtdv1ob.css
│  │  │  ├─ 포스터1-CzI1lrXX.png
│  │  │  ├─ 포스터2-p9RBIL6f.png
│  │  │  └─ 포스터3-BomAL1YX.png
│  │  ├─ favicon.svg
│  │  ├─ icons.svg
│  │  └─ index.html
│  ├─ eslint.config.js
│  ├─ index.html
│  ├─ package-lock.json
│  ├─ package.json
│  ├─ postcss.config.js
│  ├─ public
│  │  ├─ favicon.svg
│  │  └─ icons.svg
│  ├─ src
│  │  ├─ App.css
│  │  ├─ App.jsx
│  │  ├─ api
│  │  │  ├─ auth.js
│  │  │  ├─ axios.js
│  │  │  ├─ booking.js
│  │  │  └─ concert.js
│  │  ├─ assets
│  │  │  ├─ hero.png
│  │  │  ├─ react.svg
│  │  │  ├─ vite.svg
│  │  │  ├─ 포스터4.png
│  │  │  ├─ 포스터5.png
│  │  │  ├─ 포스터6.png
│  │  │  ├─ 포스터7.png
│  │  │  ├─ 포스터1.png
│  │  │  ├─ 포스터2.png
│  │  │  └─ 포스터3.png
│  │  ├─ components
│  │  │  ├─ common
│  │  │  │  ├─ Button.jsx
│  │  │  │  ├─ EmptyState.jsx
│  │  │  │  ├─ FilterTabs.jsx
│  │  │  │  ├─ LoadingSpinner.jsx
│  │  │  │  ├─ PageContainer.jsx
│  │  │  │  ├─ SectionHeader.jsx
│  │  │  │  └─ StatusBadge.jsx
│  │  │  └─ concert
│  │  │     ├─ ConcertCard.jsx
│  │  │     ├─ FeaturedCard.jsx
│  │  │     └─ HeroCarousel.jsx
│  │  ├─ constants
│  │  │  └─ posterMap.js
│  │  ├─ hooks
│  │  │  └─ useQueuePolling.js
│  │  ├─ index.css
│  │  ├─ main.jsx
│  │  ├─ pages
│  │  │  ├─ BookingPage.jsx
│  │  │  ├─ ConcertDetailPage.jsx
│  │  │  ├─ HomePage.jsx
│  │  │  ├─ LoginPage.jsx
│  │  │  ├─ MyBookingsPage.jsx
│  │  │  ├─ PaymentFailPage.jsx
│  │  │  ├─ PaymentPage.jsx
│  │  │  ├─ PaymentSuccessPage.jsx
│  │  │  ├─ QueuePage.jsx
│  │  │  └─ RegisterPage.jsx
│  │  └─ store
│  │     └─ authStore.js
│  ├─ tailwind.config.js
│  └─ vite.config.js
├─ gradle
│  └─ wrapper
│     ├─ gradle-wrapper.jar
│     └─ gradle-wrapper.properties
├─ gradlew
├─ gradlew.bat
├─ spy.log
└─ src
   ├─ main
   │  ├─ java
   │  │  └─ studying
   │  │     └─ blog
   │  │        ├─ BlogApplication.java
   │  │        ├─ config
   │  │        │  ├─ CustomPrincipal.java
   │  │        │  ├─ DataInitializer.java
   │  │        │  ├─ JwtProperties.java
   │  │        │  ├─ TokenAuthenticationFilter.java
   │  │        │  ├─ TokenProvider.java
   │  │        │  ├─ WebOAuthSecurityConfig.java
   │  │        │  ├─ WebSecurityConfig.java
   │  │        │  └─ oauth
   │  │        │     ├─ OAuth2AuthorizationRequestBasedOnCookieRepository.java
   │  │        │     ├─ OAuth2SuccessHandler.java
   │  │        │     └─ OAuth2UserCustomService.java
   │  │        ├─ controller
   │  │        │  ├─ AdminConcertApiController.java
   │  │        │  ├─ AdminConcertViewController.java
   │  │        │  ├─ BookingApiController.java
   │  │        │  ├─ ConcertApiController.java
   │  │        │  ├─ ConcertQueueApiController.java
   │  │        │  ├─ ConcertViewController.java
   │  │        │  ├─ CouponApiController.java
   │  │        │  ├─ MyPageApiController.java
   │  │        │  ├─ PaymentApiController.java
   │  │        │  ├─ TokenApiController.java
   │  │        │  ├─ UserApiController.java
   │  │        │  └─ UserViewController.java
   │  │        ├─ domain
   │  │        │  ├─ Booking.java
   │  │        │  ├─ BookingStatus.java
   │  │        │  ├─ Concert.java
   │  │        │  ├─ ConcertStatus.java
   │  │        │  ├─ Coupon.java
   │  │        │  ├─ CouponIssue.java
   │  │        │  ├─ OutboxStatus.java
   │  │        │  ├─ Payment.java
   │  │        │  ├─ PaymentCompensationOutbox.java
   │  │        │  ├─ PaymentStatus.java
   │  │        │  ├─ RefreshToken.java
   │  │        │  ├─ Role.java
   │  │        │  └─ User.java
   │  │        ├─ dto
   │  │        │  ├─ AddUserRequest.java
   │  │        │  ├─ BookingAdminResponse.java
   │  │        │  ├─ BookingResponse.java
   │  │        │  ├─ BookingResult.java
   │  │        │  ├─ ConcertAdminUpsertRequest.java
   │  │        │  ├─ ConcertCreateRequest.java
   │  │        │  ├─ ConcertResponse.java
   │  │        │  ├─ CouponIssueResponse.java
   │  │        │  ├─ CreateAccessTokenRequest.java
   │  │        │  ├─ CreateAccessTokenResponse.java
   │  │        │  ├─ MyBookingResponse.java
   │  │        │  ├─ PaymentRequest.java
   │  │        │  ├─ PaymentResponse.java
   │  │        │  └─ TossConfirmRequest.java
   │  │        ├─ exception
   │  │        │  └─ GlobalExceptionHandler.java
   │  │        ├─ experiments
   │  │        │  ├─ e1
   │  │        │  │  ├─ domain
   │  │        │  │  │  ├─ Coupon.java
   │  │        │  │  │  └─ CouponIssue.java
   │  │        │  │  ├─ repository
   │  │        │  │  │  ├─ CouponIssueRepository.java
   │  │        │  │  │  └─ CouponRepository.java
   │  │        │  │  └─ strategy
   │  │        │  │     ├─ CouponStrategyA.java
   │  │        │  │     ├─ CouponStrategyB.java
   │  │        │  │     └─ CouponStrategyC.java
   │  │        │  ├─ e3
   │  │        │  │  ├─ domain
   │  │        │  │  │  ├─ IdempotencyKey.java
   │  │        │  │  │  └─ ProcessedEvent.java
   │  │        │  │  ├─ repository
   │  │        │  │  │  ├─ IdempotencyKeyRepository.java
   │  │        │  │  │  └─ ProcessedEventRepository.java
   │  │        │  │  └─ strategy
   │  │        │  │     ├─ IdempotencyStrategyA.java
   │  │        │  │     ├─ IdempotencyStrategyB.java
   │  │        │  │     └─ IdempotencyStrategyC.java
   │  │        │  └─ e4
   │  │        │     ├─ domain
   │  │        │     │  ├─ Outbox.java
   │  │        │     │  └─ OutboxStatus.java
   │  │        │     ├─ repository
   │  │        │     │  └─ OutboxRepository.java
   │  │        │     └─ strategy
   │  │        │        ├─ CompensationStrategyA.java
   │  │        │        └─ CompensationStrategyB.java
   │  │        ├─ repository
   │  │        │  ├─ BookingRepository.java
   │  │        │  ├─ ConcertRepository.java
   │  │        │  ├─ CouponIssueRepository.java
   │  │        │  ├─ CouponRepository.java
   │  │        │  ├─ PaymentCompensationOutboxRepository.java
   │  │        │  ├─ PaymentRepository.java
   │  │        │  ├─ RefreshTokenRepository.java
   │  │        │  └─ UserRepository.java
   │  │        ├─ scheduler
   │  │        │  ├─ BookingExpiryScheduler.java
   │  │        │  ├─ ConcertQueueScheduler.java
   │  │        │  └─ PaymentCompensationScheduler.java
   │  │        ├─ service
   │  │        │  ├─ BookingService.java
   │  │        │  ├─ ConcertService.java
   │  │        │  ├─ CouponService.java
   │  │        │  ├─ MyPageService.java
   │  │        │  ├─ PaymentService.java
   │  │        │  ├─ QueueService.java
   │  │        │  ├─ RefreshTokenService.java
   │  │        │  ├─ TokenService.java
   │  │        │  ├─ UserDetailService.java
   │  │        │  └─ UserService.java
   │  │        └─ util
   │  │           └─ CookieUtil.java
   │  └─ resources
   │     ├─ application-local.yml
   │     ├─ application-prod.yml
   │     ├─ application.yml
   │     ├─ static
   │     │  └─ js
   │     │     └─ token.js
   │     └─ templates
   │        ├─ oauthLogin.html
   │        └─ signup.html
   └─ test
      ├─ java
      │  └─ studying
      │     └─ blog
      │        ├─ BlogApplicationTests.java
      │        ├─ config
      │        │  └─ jwt
      │        │     ├─ JwtFactory.java
      │        │     └─ TokenProviderTest.java
      │        ├─ controller
      │        │  ├─ CouponApiControllerTest.java
      │        │  └─ TokenApiControllerTest.java
      │        ├─ domain
      │        │  ├─ ConcertDecreaseBookedTest.java
      │        │  ├─ CouponIssueRepositoryTest.java
      │        │  └─ CouponRepositoryTest.java
      │        ├─ experiments
      │        │  ├─ e1
      │        │  │  └─ CouponStockExperimentTest.java
      │        │  ├─ e3
      │        │  │  └─ IdempotencyExperimentTest.java
      │        │  └─ e4
      │        │     └─ CompensationExperimentTest.java
      │        ├─ scheduler
      │        │  ├─ BookingExpirySchedulerTest.java
      │        │  └─ PaymentCompensationSchedulerTest.java
      │        ├─ service
      │        │  ├─ CouponIssueConcurrencyTest.java
      │        │  ├─ CouponServiceTest.java
      │        │  ├─ EnrollConcurrencyTest.java
      │        │  ├─ EnrollServiceTest.java
      │        │  ├─ PaymentServiceFailRateTest.java
      │        │  └─ QueueServiceIntegrationTest.java
      │        └─ support
      │           └─ RedisTestSupport.java
      └─ resources
         └─ application-test.yml

```