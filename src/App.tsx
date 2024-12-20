import { useState, useRef } from 'react';
import { CanvasRef, Canvas, Node, EdgeData, NodeData, useSelection, removeAndUpsertNodes, PortData, detectCircular, Edge, removeEdge } from 'reaflow';
import { useDragControls } from 'framer-motion';
import classNames from 'classnames';
import { useProximityDnD } from './helpers/useProximityDnD';
import { DraggableStmtSideBar } from './components/StmtSideBar/DraggableStmtSideBar';
import { StmtSettingSideBar } from './components/StmtSetting/StmtSetting';

import "./App.css";
import { NODE_ID_ROOT } from './constants/nodes';

function App() {
    // 流程图数据
    const [enteredNode, setEnteredNode] = useState<NodeData | null>(null);
    const [droppable, setDroppable] = useState<boolean>(false);
    const [nodes, setNodes] = useState<NodeData[]>([
        {
            id: 'root',
            text: 'StartNode'
        }
    ]);
    const [edges, setEdges] = useState<EdgeData[]>([]);
    const canvasRef = useRef<CanvasRef | null>(null);
    
    // 拖放
    const dragControls = useDragControls();
    const {
        onDragStart, 
        onDrag, 
        onDragEnd,
        activeDrag
    } = useProximityDnD({
        canvasRef, setEnteredNode, setDroppable, dragControls, droppable, nodes, setNodes, enteredNode, edges, setEdges
    });
    
    // 选中&设置
    const {
        selections,
        onCanvasClick,
        onClick,
        onKeyDown,
        clearSelections
    } = useSelection({
        nodes,
        edges,
        onDataChange: (n, e) => {
            console.info('Data changed', n, e);
            setNodes(n);
            setEdges(e);
        },
        onSelection: s => {
            console.info('Selection', s);
        }
    });
    
    return (
        <>
        <div className="container">
        <DraggableStmtSideBar className="left"
        stmtList={['Loop', 'If', 'SetValue']}
        onDragStart={onDragStart}
        onDrag={onDrag}
        onDragEnd={onDragEnd}
        activeDrag={activeDrag}
        dragControls={dragControls} />
        <div className="mid">
        <Canvas
        // pannable={false}
        panType='scroll'
        ref={canvasRef}
        nodes={nodes}
        edges={edges}
        selections={selections}
        node={
            (n => (
                <Node
                {...n}
                style={{
                    strokeWidth: enteredNode?.id === n.id && droppable ? 5 : 1
                }}
                onClick={(event, node) => {
                    console.log('Selecting Node', event, node);
                    onClick(event, node);
                }}
                onKeyDown={(event, node) => {
                    console.log('Keydown Event', node, event);
                    onKeyDown(event);
                }} 
                // onRemove={(_event, node) => {
                //     const result = removeAndUpsertNodes(nodes, edges, node);
                //     setEdges(result.edges);
                //     setNodes(result.nodes);
                //     clearSelections();
                // }}
                removable={false}
                linkable={true}
                className={classNames({ closest: enteredNode?.id === n.id })}
                />
            ))
        }
        edge={
            (e => (
                <Edge 
                {...e}
                onClick={(event, edge) => {
                    console.log('Selecting Edge', event, edge);
                    onClick(event, edge);
                }} 
                onRemove={(_event, edge) => {
                    const newEdges = removeEdge(edges, edge);
                    setEdges(newEdges);
                    clearSelections();
                }}
                />
            ))
        }
        onNodeLink={(_event: any, from: NodeData, to: NodeData) => {
            const id = `${from.id}-${to.id}`;
            setEdges([...edges, {
                id,
                from: from.id,
                to: to.id
            }]);
        }}
        onNodeLinkCheck={(_event: any, from: NodeData, to: NodeData, _fromPort: PortData) => {
            if (to.id === NODE_ID_ROOT) {
                return false;
            }
            // 每个节点的入度不能超过 1
            let inCount = 0;
            for (let edge of edges) {
                if (edge.to === to.id) {
                    inCount += 1;
                }
                if (inCount >= 1) {
                    console.log(edge);
                    return false;
                }
            }
            // 不允许有环
            return !detectCircular(nodes, edges, from, to);
        }}
        onCanvasClick={event => {
            console.log('Canvas Clicked', event);
            onCanvasClick();
        }}
        onLayoutChange={layout => console.info('Layout', layout)}
        onMouseEnter={() => setDroppable(true)}
        onMouseLeave={() => setDroppable(false)}
        />
        </div>
        <StmtSettingSideBar className="right" currentSelection={selections}/>
        </div>
        </>
    )
}

export default App
