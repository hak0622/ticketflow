package studying.blog.fault;

import org.springframework.beans.factory.annotation.Autowired;
import studying.blog.service.InventoryObservation;
import static org.assertj.core.api.Assertions.*;

/** Runs the existing real Redis fault matrix with observation at each injected boundary. */
class InventoryBoundaryObservationIntegrationTest extends RedisLuaPartialWriteIntegrationTest {
    @Autowired InventoryObservation observation;
    @Override void fault(String operation, int boundary) throws Exception {
        super.fault(operation, boundary);
        observation.observe();
        double expected=boundary==4?0:1;
        assertThat(observation.snapshot().reserve()).isEqualTo(operation.equals("reserve")?expected:0);
        assertThat(observation.snapshot().release()).isEqualTo(operation.equals("release")?expected:0);
    }
}
