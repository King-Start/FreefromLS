package com.sunshine.freeform.ui.freeform

import android.content.ComponentName

/**
 * @author sunshine
 * @date 2021/3/18
 * 为了满足单一性栈，采用集合特性来实现栈
 */
class StackSet {

    private val elementData = ArrayList<FreeformViewAbs>()

    /**
     * 将元素放到栈顶，但同时保证单一性
     * 时间复杂度O(n)
     */
    fun push(element: FreeformViewAbs) {
        elementData.remove(element)
        elementData.add(element)
    }

    fun pop(): FreeformViewAbs {
        return elementData.removeAt(elementData.size - 1)
    }

    fun peek(): FreeformViewAbs {
        return elementData[elementData.size - 1]
    }

    fun remove(element: FreeformViewAbs) {
        elementData.remove(element)
    }

    fun clean() {
        // Do not mutate an ArrayList from inside forEach: that throws
        // ConcurrentModificationException and can leave a virtual display alive.
        val elements = elementData.toList()
        elementData.clear()
        elements.forEach { element ->
            runCatching { element.destroy() }
        }
    }

    fun size(): Int {
        return elementData.size
    }

    fun get(index: Int): FreeformViewAbs {
        return elementData[index]
    }

    fun getByComponentName(componentName: ComponentName, userId: Int): FreeformViewAbs? {
        // A launch may carry an Intent instead of componentName.  Never force
        // unwrap it while looking for an existing window.
        return elementData.firstOrNull {
            it.config.componentName == componentName && it.config.userId == userId
        }
    }
}