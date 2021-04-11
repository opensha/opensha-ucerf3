package scratch.UCERF3.utils;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import org.dom4j.DocumentException;
import org.junit.Test;
import scratch.UCERF3.FaultSystemSolution;

public class LoadInversionSolutionIntegrationTest {

	/**
	 * FaultSystemIO.loadSol will failing unless mags are set first in super.init()
	 * 
	 * @throws DocumentException
	 * @throws IOException
	 */
	@Test
	public void testLoadInversionSolutionHasClusterRuptures() throws IOException, DocumentException {
		FaultSystemSolution loadedSolution = FaultSystemIO.loadSol(new File("/tmp/testAlpineVernonInversion.zip"));
		assertEquals(3101, loadedSolution.getRupSet().getClusterRuptures().size());
	}

}
