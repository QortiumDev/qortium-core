package org.qortium.utils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

public class RandomizeList {
	private static final Random random = new Random();

	public static <T> List<T> randomize(List<T> inputList) {
		List<T> outputList = new ArrayList<T>();

		Iterator<T> inputIterator = inputList.iterator();
		while (inputIterator.hasNext()) {
			T element = inputIterator.next();

			if (outputList.isEmpty()) {
				outputList.add(element);
			} else {
				int outputIndex = random.nextInt(outputList.size() + 1);
				outputList.add(outputIndex, element);
			}
		}

		return outputList;
	}

}
