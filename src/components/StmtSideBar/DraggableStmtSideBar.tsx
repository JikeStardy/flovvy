import { motion } from 'framer-motion';
import { Portal } from 'reablocks';
import "./DraggableStmtSideBar.css";

export const DraggableStmtSideBar = ({ stmtList, onDragStart, onDrag, onDragEnd, activeDrag, dragControls, className }) => {
    
    const stmts = stmtList.map((stmt: string, idx: number) => 
        <motion.div key={idx} className="block" onMouseDown={event => onDragStart(event, stmt)} onMouseUp={event => onDragEnd(event)}>
        {stmt}
        </motion.div>
    );

    return (
        <div className={className}>
            <div className="stmtlist">{stmts}</div>
            <Portal>
            <motion.div
            drag
            dragControls={dragControls}
            className="dragger"
            onDrag={onDrag}
            onDragEnd={onDragEnd}
            >
            {activeDrag && (
                <div className="block">
                {activeDrag}
                </div>
            )}
            </motion.div>
            </Portal>
        </div>
    );
};