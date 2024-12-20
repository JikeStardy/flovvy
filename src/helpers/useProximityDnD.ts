import { useState } from 'react';
import { useProximity, addNodeAndEdge, NodeData } from 'reaflow';

export interface ProximityDnDResult {
    /**
     * The matched id of the node.
     */
    match: string | null;
  
    /**
     * Event for drag started.
     */
    onDragStart: (event: PointerEvent) => void;
  
    /**
     * Event for active dragging.
     */
    onDrag: (event: PointerEvent) => void;
  
    /**
     * Event for drag ended.
     */
    onDragEnd: (event: PointerEvent) => void;

    activeDrag: string | null;
  }

export const useProximityDnD = ({ canvasRef, setEnteredNode, setDroppable, dragControls, droppable, nodes, setNodes, enteredNode, edges, setEdges }) => {
    const [activeDrag, setActiveDrag] = useState<string | null>(null);

    const {
        onDragStart: onProximityDragStart,
        onDrag: onProximityDrag,
        onDragEnd: onProximityDragEnd
    } = useProximity({
        canvasRef,
        onDistanceChange: (distance: number | null) => {
            console.info('Distance Changed', distance);
        },
        onIntersects: (match: string) => {
            console.info('Node Intersected', match);
        },
        onMatchChange: (match: string | null) => {
            console.info('Match Changed!', match);
            
            let matchNode: NodeData | null = null;
            if (match) {
                matchNode = nodes.find(n => n.id === match);
            }
            
            setEnteredNode(matchNode);
            // setDroppable(matchNode !== null);
        }
    });

    const onDragStart =  (event, data) => {
        console.info('Start of Dragging', event, data);
        onProximityDragStart(event);
        setActiveDrag(data);
        dragControls.start(event, { snapToCursor: true });
    };

    const onDrag = (event) => {
        onProximityDrag(event);
    };

    const onDragEnd = (event) => {
        console.info('Stop of Dragging', event);
        onProximityDragEnd(event);
        
        if (droppable) {
            const id = `${activeDrag}-${Math.floor(Math.random() * (100 - 1 + 1)) + 1}`;
            
            // This is for demonstration purposes, you should
            // tweak this to fit your business infoic
            // if (enteredNode?.id === '2') {
            //     setNodes([
            //         ...nodes,
            //         {
            //             id,
            //             text: id,
            //             parent: '2'
            //         }
            //     ]);
            // } else {
            const result = addNodeAndEdge(
                nodes,
                edges,
                {
                    id,
                    text: id,
                    parent: enteredNode?.parent
                },
                enteredNode
            );
            setNodes(result.nodes);
            setEdges(result.edges);
            // }
        }
        
        setDroppable(false);
        setActiveDrag(null);
        setEnteredNode(null);
    };

    return { onDragStart, onDrag, onDragEnd, activeDrag } as ProximityDnDResult;
}